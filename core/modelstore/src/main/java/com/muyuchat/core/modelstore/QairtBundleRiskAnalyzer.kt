package com.muyuchat.core.modelstore

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

enum class QairtGraphRiskLevel {
    UNKNOWN,
    LOW,
    ELEVATED,
    HIGH
}

/**
 * Identity captured after a real QAIRT smoke has passed. A verified result is
 * deliberately tied to all three values: a context binary can be compatible
 * with one chipset/runtime pairing and fail on another.
 */
data class QairtBundleRuntimeIdentity(
    val bundleSha256: String,
    val chipset: String,
    val runtimeFingerprint: String
) {
    val isComplete: Boolean
        get() = bundleSha256.isNotBlank() && chipset.isNotBlank() && runtimeFingerprint.isNotBlank()
}

enum class QairtExecutionAdmissionMode {
    /** A prior real-device smoke verified this exact bundle, chipset and runtime. */
    VERIFIED_ALLOW,

    /** Attempt only in an isolated worker with a short create/generate/destroy smoke. */
    ISOLATED_DRY_RUN
}

/**
 * A static graph inspection is advisory. It can select a safer execution path,
 * but it must never suppress downloading a model or permanently refuse a run.
 */
data class QairtExecutionAdmission(
    val mode: QairtExecutionAdmissionMode,
    val graphRisk: QairtGraphRiskLevel,
    val memoryAdvisory: String?,
    val message: String
) {
    val canAttempt: Boolean
        get() = true

    val requiresIsolatedDryRun: Boolean
        get() = mode == QairtExecutionAdmissionMode.ISOLATED_DRY_RUN
}

data class QairtGraphMemoryProfile(
    val metadataFileCount: Int,
    val parsedMetadataFileCount: Int,
    val modelName: String?,
    val precision: String?,
    val maxKvSpan: Int?,
    val kvInputTensorCount: Int,
    val estimatedKvInputTensorCount: Int,
    val estimatedKvInputBytes: Long,
    val riskLevel: QairtGraphRiskLevel,
    val parseErrors: List<String>
) {
    val kvByteEstimateComplete: Boolean
        get() = kvInputTensorCount > 0 && estimatedKvInputTensorCount == kvInputTensorCount

    /**
     * Legacy compatibility hook. Static KV/RAM profiles are advisory only, so
     * callers must not use this method to reject a download or a load attempt.
     * Use [admissionForDeviceMemory] to choose verified execution versus an
     * isolated dry-run worker.
     */
    fun blockerForTotalRam(totalRamBytes: Long): String? {
        return null
    }

    /**
     * Legacy compatibility hook. It intentionally returns no blocker: memory
     * telemetry is too imprecise to turn a QAIRT package into an un-downloadable
     * or un-runnable model. New callers should inspect [admissionForDeviceMemory]
     * and launch unknown combinations in an isolated worker.
     */
    fun blockerForDeviceMemory(totalRamBytes: Long, availableRamBytes: Long): String? {
        return null
    }

    /**
     * Decides how to attempt QAIRT execution without treating model metadata or
     * RAM as a static deny-list. Exact previously verified combinations are
     * allowed normally; every other combination remains runnable through an
     * isolated, short create/generate/destroy dry-run.
     */
    fun admissionForDeviceMemory(
        totalRamBytes: Long,
        availableRamBytes: Long,
        observedIdentity: QairtBundleRuntimeIdentity? = null,
        verifiedIdentities: Set<QairtBundleRuntimeIdentity> = emptySet()
    ): QairtExecutionAdmission {
        val memoryAdvisory = memoryAdvisory(totalRamBytes, availableRamBytes)
        val verified = observedIdentity
            ?.takeIf(QairtBundleRuntimeIdentity::isComplete)
            ?.let { identity -> identity in verifiedIdentities }
            ?: false
        return if (verified) {
            QairtExecutionAdmission(
                mode = QairtExecutionAdmissionMode.VERIFIED_ALLOW,
                graphRisk = riskLevel,
                memoryAdvisory = memoryAdvisory,
                message = buildString {
                    append("Verified QAIRT bundle/chipset/runtime combination may run. ")
                    append(diagnosticSummary())
                    memoryAdvisory?.let { advisory -> append(". Advisory: ").append(advisory) }
                }
            )
        } else {
            QairtExecutionAdmission(
                mode = QairtExecutionAdmissionMode.ISOLATED_DRY_RUN,
                graphRisk = riskLevel,
                memoryAdvisory = memoryAdvisory,
                message = buildString {
                    append("QAIRT bundle/chipset/runtime combination is not verified; ")
                    append("run a short isolated create/generate/destroy dry-run before normal use. ")
                    append("Static KV/RAM analysis is advisory and does not block download or execution. ")
                    append(diagnosticSummary())
                    memoryAdvisory?.let { advisory -> append(". Advisory: ").append(advisory) }
                }
            )
        }
    }

    fun diagnosticSummary(): String = buildString {
        append("qairtMetadata=").append(parsedMetadataFileCount).append('/').append(metadataFileCount)
        append(", kvSpan=").append(maxKvSpan ?: "unknown")
        append(", kvInputs=").append(estimatedKvInputTensorCount).append('/').append(kvInputTensorCount)
        append(", kvBytes=").append(estimatedKvInputBytes)
        append(", kvEstimate=").append(if (kvByteEstimateComplete) "complete" else "incomplete")
        append(", graphRisk=").append(riskLevel.name.lowercase())
        append(", admission=isolated-dry-run-unless-exact-combination-verified")
    }

    private fun memoryAdvisory(totalRamBytes: Long, availableRamBytes: Long): String? {
        if (totalRamBytes <= 0L || availableRamBytes <= 0L) {
            return "Device memory telemetry is unavailable; use the isolated dry-run result instead of a static RAM decision."
        }
        val requiredAvailableBytes = maxOf(
            MIN_AVAILABLE_RAM_BYTES,
            saturatingAdd(
                KV_RUNTIME_FIXED_HEADROOM_BYTES,
                saturatingMultiply(estimatedKvInputBytes, KV_RUNTIME_MULTIPLIER)
            )
        )
        return if (availableRamBytes < requiredAvailableBytes) {
            "Available memory ($availableRamBytes bytes) is below the advisory native headroom " +
                "($requiredAvailableBytes bytes); close background apps before the isolated dry-run."
        } else {
            null
        }
    }

    private companion object {
        private const val MIN_AVAILABLE_RAM_BYTES = 2L * 1024L * 1024L * 1024L
        private const val KV_RUNTIME_FIXED_HEADROOM_BYTES = 1L * 1024L * 1024L * 1024L
        private const val KV_RUNTIME_MULTIPLIER = 4L

        private fun saturatingMultiply(left: Long, right: Long): Long {
            if (left == 0L || right == 0L) return 0L
            if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
            return left * right
        }

        private fun saturatingAdd(left: Long, right: Long): Long {
            if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE
            return left + right
        }
    }
}

object QairtBundleRiskAnalyzer {
    private const val MAX_METADATA_BYTES = 16L * 1024L * 1024L
    private const val MAX_JSON_DEPTH = 64

    fun analyze(bundleDir: File): QairtGraphMemoryProfile {
        if (!bundleDir.isDirectory) return emptyProfile()
        val metadataFiles = bundleDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.name.equals("metadata.json", ignoreCase = true) &&
                    file.length() in 1..MAX_METADATA_BYTES
            }
            .toList()
        if (metadataFiles.isEmpty()) return emptyProfile()

        val state = AnalysisState()
        metadataFiles.forEach { file ->
            runCatching {
                val root = JSONTokener(file.readText(Charsets.UTF_8)).nextValue()
                state.parsedMetadataFiles += 1
                visitJson(root, key = null, state = state, depth = 0)
            }.onFailure { error ->
                state.parseErrors += (file.name + ": " + error.message.orEmpty()).trim()
            }
        }
        val maxKvSpan = state.maxKvSpan
        val kvByteEstimateComplete = state.kvInputTensorCount > 0 &&
            state.estimatedKvInputTensorCount == state.kvInputTensorCount
        val riskLevel = when {
            maxKvSpan == null || !kvByteEstimateComplete -> QairtGraphRiskLevel.UNKNOWN
            maxKvSpan >= 3_072 || state.estimatedKvInputBytes > MAX_SAFE_KV_INPUT_BYTES ->
                QairtGraphRiskLevel.HIGH
            maxKvSpan > 1_024 -> QairtGraphRiskLevel.ELEVATED
            else -> QairtGraphRiskLevel.LOW
        }
        return QairtGraphMemoryProfile(
            metadataFileCount = metadataFiles.size,
            parsedMetadataFileCount = state.parsedMetadataFiles,
            modelName = state.modelName,
            precision = state.precision,
            maxKvSpan = state.maxKvSpan,
            kvInputTensorCount = state.kvInputTensorCount,
            estimatedKvInputTensorCount = state.estimatedKvInputTensorCount,
            estimatedKvInputBytes = state.estimatedKvInputBytes,
            riskLevel = riskLevel,
            parseErrors = state.parseErrors.toList()
        )
    }

    private fun visitJson(value: Any?, key: String?, state: AnalysisState, depth: Int) {
        if (depth > MAX_JSON_DEPTH || value == null || value == JSONObject.NULL) return
        when (value) {
            is JSONObject -> {
                if (key != null && isKvInputTensor(key)) {
                    recordKvTensor(value, state)
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val childKey = keys.next()
                    val child = value.opt(childKey)
                    when (childKey.lowercase()) {
                        "model_name", "modelname" ->
                            state.modelName = state.modelName ?: child?.toString()?.takeUnless { it == "null" }
                        "precision" ->
                            state.precision = state.precision ?: child?.toString()?.takeUnless { it == "null" }
                    }
                    visitJson(child, childKey, state, depth + 1)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    visitJson(value.opt(index), key, state, depth + 1)
                }
            }
        }
    }

    private fun recordKvTensor(tensor: JSONObject, state: AnalysisState) {
        state.kvInputTensorCount += 1
        val shape = tensor.optJSONArray("shape") ?: return
        if (shape.length() == 0) return
        val dimensions = mutableListOf<Long>()
        for (index in 0 until shape.length()) {
            val dimension = shape.optLong(index, -1L)
            if (dimension <= 0L) return
            dimensions += dimension
        }
        val span = dimensions.maxOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: return
        val elementBytes = dtypeBytes(tensor.optString("dtype")) ?: return
        state.maxKvSpan = maxOf(state.maxKvSpan ?: 0, span)
        state.estimatedKvInputTensorCount += 1
        val tensorBytes = dimensions.fold(1L) { total, dimension -> saturatingMultiply(total, dimension) }
        state.estimatedKvInputBytes = saturatingAdd(
            state.estimatedKvInputBytes,
            saturatingMultiply(tensorBytes, elementBytes.toLong())
        )
    }

    private fun isKvInputTensor(key: String): Boolean {
        val normalized = key.lowercase()
        return (normalized.startsWith("past_key_") || normalized.startsWith("past_value_")) &&
            (normalized.endsWith("_in") || normalized.endsWith("_input"))
    }

    private fun dtypeBytes(dtype: String): Int? = when (dtype.lowercase()) {
        "bool", "int8", "uint8" -> 1
        "float16", "fp16", "bfloat16", "bf16", "int16", "uint16" -> 2
        "float32", "fp32", "int32", "uint32" -> 4
        "float64", "fp64", "int64", "uint64" -> 8
        else -> null
    }

    private fun saturatingMultiply(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE
        return left + right
    }

    private fun emptyProfile() = QairtGraphMemoryProfile(
        metadataFileCount = 0,
        parsedMetadataFileCount = 0,
        modelName = null,
        precision = null,
        maxKvSpan = null,
        kvInputTensorCount = 0,
        estimatedKvInputTensorCount = 0,
        estimatedKvInputBytes = 0L,
        riskLevel = QairtGraphRiskLevel.UNKNOWN,
        parseErrors = emptyList()
    )

    private data class AnalysisState(
        var parsedMetadataFiles: Int = 0,
        var modelName: String? = null,
        var precision: String? = null,
        var maxKvSpan: Int? = null,
        var kvInputTensorCount: Int = 0,
        var estimatedKvInputTensorCount: Int = 0,
        var estimatedKvInputBytes: Long = 0L,
        val parseErrors: MutableList<String> = mutableListOf()
    )

    private const val MAX_SAFE_KV_INPUT_BYTES = 256L * 1024L * 1024L
}

package com.muyuchat.core.mediatek

enum class MediaTekAnimaDiagnosticStage {
    PRELUDE,
    DIT_BLOCK,
    FINAL,
}

enum class MediaTekAnimaCfgBranch {
    POSITIVE,
    NEGATIVE,
}

data class MediaTekAnimaDiagnosticStats(
    val count: Int,
    val finiteCount: Int,
    val mean: Double?,
    val standardDeviation: Double?,
    val maxAbs: Double?,
)

/** Tensor-only diagnostics; this record intentionally excludes prompts and token IDs. */
data class MediaTekAnimaDiagnosticSample(
    val stage: MediaTekAnimaDiagnosticStage,
    val stepIndex: Int,
    val cfgBranch: MediaTekAnimaCfgBranch,
    val blockIndex: Int?,
    val graphFile: String,
    val inputTensorName: String,
    val outputTensorName: String,
    val inputStats: MediaTekAnimaDiagnosticStats,
    val outputStats: MediaTekAnimaDiagnosticStats,
    val stdDriftRatio: Double?,
)

internal fun MediaTekTensorData.Float32.diagnosticStats(): MediaTekAnimaDiagnosticStats {
    val finiteCount = values.count { it.isFinite() }
    if (finiteCount == 0) {
        return MediaTekAnimaDiagnosticStats(
            count = values.size,
            finiteCount = 0,
            mean = null,
            standardDeviation = null,
            maxAbs = null,
        )
    }
    var sum = 0.0
    var maxAbs = 0.0
    values.forEach { value ->
        if (value.isFinite()) {
            sum += value.toDouble()
            maxAbs = maxOf(maxAbs, kotlin.math.abs(value.toDouble()))
        }
    }
    val mean = sum / finiteCount
    var squaredDifference = 0.0
    values.forEach { value ->
        if (value.isFinite()) {
            val difference = value.toDouble() - mean
            squaredDifference += difference * difference
        }
    }
    return MediaTekAnimaDiagnosticStats(
        count = values.size,
        finiteCount = finiteCount,
        mean = mean,
        standardDeviation = kotlin.math.sqrt(squaredDifference / finiteCount),
        maxAbs = maxAbs,
    )
}

internal fun createMediaTekAnimaDiagnosticSample(
    stage: MediaTekAnimaDiagnosticStage,
    stepIndex: Int,
    cfgBranch: MediaTekAnimaCfgBranch,
    blockIndex: Int?,
    graphFile: String,
    inputTensorName: String,
    outputTensorName: String,
    inputStats: MediaTekAnimaDiagnosticStats,
    outputStats: MediaTekAnimaDiagnosticStats,
): MediaTekAnimaDiagnosticSample {
    val inputStd = inputStats.standardDeviation
    val outputStd = outputStats.standardDeviation
    val driftRatio = when {
        inputStd == null || outputStd == null -> null
        inputStd == 0.0 && outputStd == 0.0 -> 1.0
        inputStd == 0.0 -> null
        else -> (outputStd / inputStd).takeIf { it.isFinite() }
    }
    return MediaTekAnimaDiagnosticSample(
        stage = stage,
        stepIndex = stepIndex,
        cfgBranch = cfgBranch,
        blockIndex = blockIndex,
        graphFile = graphFile,
        inputTensorName = inputTensorName,
        outputTensorName = outputTensorName,
        inputStats = inputStats,
        outputStats = outputStats,
        stdDriftRatio = driftRatio,
    )
}

internal fun createMediaTekAnimaDiagnosticSample(
    stage: MediaTekAnimaDiagnosticStage,
    stepIndex: Int,
    cfgBranch: MediaTekAnimaCfgBranch,
    blockIndex: Int?,
    graphFile: String,
    input: MediaTekTensorData.Float32,
    output: MediaTekTensorData.Float32,
    inputTensorName: String = input.name,
    outputTensorName: String = output.name,
) = createMediaTekAnimaDiagnosticSample(
    stage = stage,
    stepIndex = stepIndex,
    cfgBranch = cfgBranch,
    blockIndex = blockIndex,
    graphFile = graphFile,
    inputTensorName = inputTensorName,
    outputTensorName = outputTensorName,
    inputStats = input.diagnosticStats(),
    outputStats = output.diagnosticStats(),
)

internal fun reportFailedOutputDiagnostic(
    onDiagnostic: ((MediaTekAnimaDiagnosticSample) -> Unit)?,
    stage: MediaTekAnimaDiagnosticStage,
    stepIndex: Int,
    cfgBranch: MediaTekAnimaCfgBranch,
    blockIndex: Int?,
    inputTensorName: String,
    input: MediaTekTensorData.Float32,
    error: MediaTekGraphExecutionException,
) {
    val callback = onDiagnostic ?: return
    val outputDiagnostic = error.outputDiagnostic ?: return
    callback(
        createMediaTekAnimaDiagnosticSample(
            stage = stage,
            stepIndex = stepIndex,
            cfgBranch = cfgBranch,
            blockIndex = blockIndex,
            graphFile = outputDiagnostic.graphFile,
            inputTensorName = inputTensorName,
            outputTensorName = outputDiagnostic.tensorName,
            inputStats = input.diagnosticStats(),
            outputStats = outputDiagnostic.stats,
        ),
    )
}

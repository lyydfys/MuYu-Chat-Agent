package com.muyuchat.mca

import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class LocalImageBatchExecutionMode {
    NATIVE_BATCH,
    COORDINATOR_SEQUENTIAL
}

internal data class LocalImageBatchRequestPlan(
    val requestId: String,
    val options: LocalImageGenerationOptions,
    val outputOffset: Int,
    val outputCount: Int,
    val batchLineage: ImageGenerationBatchLineage?,
    val outputLineages: List<ImageGenerationBatchLineage>
) {
    init {
        require(requestId.isNotBlank()) { "Image batch child requestId must not be blank." }
        require(outputOffset >= 0) { "Image batch output offset must be non-negative." }
        require(outputCount > 0) { "Image batch request output count must be positive." }
        require(options.batchCount == outputCount) {
            "Image batch request options must match its physical output count."
        }
        require(outputLineages.size == outputCount) {
            "Image batch request must carry one lineage per physical output."
        }
    }

    fun parentProgress(progress: LocalImageProgress): LocalImageProgress {
        if (batchLineage == null) return progress
        val totalSteps = if (progress.steps > 0) {
            Math.multiplyExact(progress.steps, batchLineage.count)
        } else {
            progress.steps
        }
        val parentStep = if (progress.steps > 0) {
            Math.addExact(
                Math.multiplyExact(batchLineage.index, progress.steps),
                progress.step.coerceIn(0, progress.steps)
            )
        } else {
            progress.step
        }
        return progress.copy(
            message = "第 ${batchLineage.index + 1}/${batchLineage.count} 张 · ${progress.message}",
            step = parentStep,
            steps = totalSteps,
            previewRevision = coordinatedPreviewRevision(
                outputIndex = batchLineage.index,
                childRevision = progress.previewRevision
            )
        )
    }
}

internal data class LocalImageBatchPlan(
    val parentRequestId: String,
    val executionMode: LocalImageBatchExecutionMode,
    val parentOptions: LocalImageGenerationOptions,
    val baseSeed: Int,
    val requests: List<LocalImageBatchRequestPlan>
) {
    val outputCount: Int
        get() = parentOptions.batchCount

    init {
        require(parentRequestId.isNotBlank()) { "Image batch parent requestId must not be blank." }
        require(outputCount in 1..ImageGenerationBatchLineage.MAX_BATCH_COUNT) {
            "Image batch count must be between 1 and ${ImageGenerationBatchLineage.MAX_BATCH_COUNT}."
        }
        require(baseSeed >= 0) { "Image batch base seed must be non-negative." }
        require(requests.isNotEmpty()) { "Image batch plan must contain at least one request." }
        require(requests.sumOf(LocalImageBatchRequestPlan::outputCount) == outputCount) {
            "Image batch requests must account for every requested output."
        }
        require(requests.flatMap(LocalImageBatchRequestPlan::outputLineages).map { it.index } ==
            (0 until outputCount).toList()
        ) { "Image batch output lineage must be contiguous." }
    }
}

internal fun planLocalImageBatch(
    parentRequestId: String,
    runtime: LocalImageRuntime,
    requestedOptions: LocalImageGenerationOptions,
    randomBaseSeed: (maxInclusive: Int) -> Int = ::defaultLocalImageBatchSeed
): LocalImageBatchPlan {
    require(parentRequestId.isNotBlank()) { "Image batch parent requestId must not be blank." }
    val count = requestedOptions.batchCount
    require(count in 1..ImageGenerationBatchLineage.MAX_BATCH_COUNT) {
        "Image batch count must be between 1 and ${ImageGenerationBatchLineage.MAX_BATCH_COUNT}."
    }
    val maxBaseSeed = Math.subtractExact(Int.MAX_VALUE, count - 1)
    val requestedSeed = requestedOptions.seed
    val baseSeed = when {
        requestedSeed == null || requestedSeed == -1 -> randomBaseSeed(maxBaseSeed).also { generated ->
            require(generated in 0..maxBaseSeed) {
                "Random image batch seed must be between 0 and $maxBaseSeed."
            }
        }
        requestedSeed < 0 -> throw IllegalArgumentException(
            "Image batch seed must be -1 (random) or a non-negative integer."
        )
        else -> requestedSeed.also { Math.addExact(it, count - 1) }
    }
    val parentOptions = requestedOptions.copy(seed = baseSeed)
    val lineages = List(count) { index ->
        ImageGenerationBatchLineage(
            parentRequestId = parentRequestId,
            index = index,
            count = count,
            seed = Math.addExact(baseSeed, index)
        )
    }
    val nativeBatch = runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP || count == 1
    val mode = if (nativeBatch) {
        LocalImageBatchExecutionMode.NATIVE_BATCH
    } else {
        LocalImageBatchExecutionMode.COORDINATOR_SEQUENTIAL
    }
    val requests = if (nativeBatch) {
        listOf(
            LocalImageBatchRequestPlan(
                requestId = parentRequestId,
                options = parentOptions,
                outputOffset = 0,
                outputCount = count,
                batchLineage = lineages.singleOrNull(),
                outputLineages = lineages
            )
        )
    } else {
        lineages.map { lineage ->
            LocalImageBatchRequestPlan(
                requestId = "$parentRequestId-batch-${lineage.index.toString().padStart(3, '0')}",
                options = parentOptions.copy(batchCount = 1, seed = lineage.seed),
                outputOffset = lineage.index,
                outputCount = 1,
                batchLineage = lineage,
                outputLineages = listOf(lineage)
            )
        }
    }
    return LocalImageBatchPlan(
        parentRequestId = parentRequestId,
        executionMode = mode,
        parentOptions = parentOptions,
        baseSeed = baseSeed,
        requests = requests
    )
}

internal suspend fun <T> executeLocalImageBatchPlan(
    plan: LocalImageBatchPlan,
    cancellationRequested: () -> Boolean,
    execute: suspend (LocalImageBatchRequestPlan) -> List<T>,
    cleanup: suspend (List<T>) -> Unit
): List<T> {
    val candidates = mutableListOf<T>()
    try {
        plan.requests.forEach { request ->
            currentCoroutineContext().ensureActive()
            if (cancellationRequested()) throw LocalImageWorkerCancelledException()
            val outputs = execute(request)
            require(outputs.size == request.outputCount) {
                "Image batch child ${request.requestId} returned ${outputs.size} output(s); " +
                    "expected ${request.outputCount}."
            }
            candidates += outputs
            currentCoroutineContext().ensureActive()
            if (cancellationRequested()) throw LocalImageWorkerCancelledException()
        }
        check(candidates.size == plan.outputCount) {
            "Image batch completed without producing every requested output."
        }
        return candidates
    } catch (error: Throwable) {
        if (candidates.isNotEmpty()) {
            runCatching { cleanup(candidates.toList()) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
        }
        throw error
    }
}

internal fun coordinatedPreviewRevision(outputIndex: Int, childRevision: Long): Long {
    require(outputIndex in 0 until ImageGenerationBatchLineage.MAX_BATCH_COUNT) {
        "Image preview output index is outside the product batch range."
    }
    if (childRevision <= 0L) return childRevision
    require(childRevision <= 0xffffffffL) { "Image preview child revision exhausted its range." }
    return (outputIndex.toLong() shl 32) or childRevision
}

private fun defaultLocalImageBatchSeed(maxInclusive: Int): Int =
    Random.Default.nextLong(from = 0L, until = maxInclusive.toLong() + 1L).toInt()

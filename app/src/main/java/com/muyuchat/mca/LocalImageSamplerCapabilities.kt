package com.muyuchat.mca

import java.io.File

/**
 * Resolves sampler admission from the installed graph profile only. Recommendation ids and device
 * discovery are intentionally absent: any package with the same executable topology gets the same
 * product contract and reaches the same real native load/execute path.
 */
internal fun LocalImageModelRecord.validateProductTaskSampler(
    taskMode: LocalImageTaskMode,
    sampleMethod: String?
): ImageSchedulerAlgorithm {
    val root = bundleRoot
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?: File(path).parentFile?.takeIf(File::isDirectory)
    val resolution = resolveLocalImageExecutionProfile(
        model = this,
        options = LocalImageGenerationOptions(
            taskMode = taskMode,
            sampleMethod = sampleMethod
        ),
        bundleRoot = root
    )
    val requestedScheduler = resolution.layers.resolved.scheduler
    validateLocalImageTaskSamplerCapability(
        profile = resolution.profile,
        taskMode = taskMode,
        requestedScheduler = requestedScheduler
    )
    return requestedScheduler
}

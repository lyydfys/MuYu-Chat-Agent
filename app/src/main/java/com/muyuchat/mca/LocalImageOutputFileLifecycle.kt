package com.muyuchat.mca

import java.io.File

/**
 * The on-disk lifecycle of one local image request output: directory, candidates, cleanup, pruning.
 *
 * Moved out of `LocalImageProvider.kt` verbatim: the canonical output directory helper, the preview
 * and request output candidates for stable-diffusion.cpp / MNN Diffusion / Qwen-Image 2.1, the
 * request-scoped cleanup, and the stale-output pruning. The regexes that identify an owned output
 * name, the age limits, and the batch bound moved with them, so there is exactly one definition of
 * each. No default value, path rule, or deletion condition was changed.
 */
private const val STABLE_DIFFUSION_OUTPUT_MAX_BATCH_COUNT = 8

/**
 * Creates a private local-image output directory and returns the exact path that native code must
 * receive. Android may expose an app directory through a symlinked alias, while the native
 * validator intentionally accepts only the canonical spelling.
 */
internal fun canonicalLocalImageOutputDirectory(cacheDirectory: File, name: String): File {
    require(name.isNotBlank() && File(name).name == name) {
        "Local image output directory name must be a single child of the app cache directory."
    }
    val canonicalCacheDirectory = cacheDirectory.canonicalFile
    require(canonicalCacheDirectory.isDirectory) {
        "App cache directory is unavailable."
    }
    val directory = File(canonicalCacheDirectory, name)
    check(directory.isDirectory || directory.mkdirs()) {
        "Unable to create the local image output directory."
    }
    val canonicalDirectory = directory.canonicalFile
    require(canonicalDirectory.parentFile == canonicalCacheDirectory) {
        "Local image output directory escaped the app cache directory."
    }
    return canonicalDirectory
}

internal fun stableDiffusionPreviewCandidates(outputFile: File): List<File> = buildList {
    repeat(2) { slot ->
        val preview = File(outputFile.absolutePath + ".preview-$slot.png")
        add(preview)
        add(File(preview.absolutePath + ".part"))
    }
}

private val STABLE_DIFFUSION_OWNED_OUTPUT_NAME = Regex(
    "^sdcpp-(?:[0-9]{1,19}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
        "[0-9a-f]{4}-[0-9a-f]{12})(?:-[2-8])?\\.png" +
        "(?:\\.part|\\.preview-[01]\\.png(?:\\.part)?)?$"
)

internal fun stableDiffusionRequestOutputCandidates(outputFile: File): List<File> {
    val parent = outputFile.absoluteFile.parentFile ?: return listOf(outputFile.absoluteFile)
    val fileName = outputFile.name
    val stem = fileName.removeSuffix(".png")
    if (stem == fileName) return listOf(outputFile.absoluteFile)
    return buildList {
        for (index in 1..STABLE_DIFFUSION_OUTPUT_MAX_BATCH_COUNT) {
            val output = if (index == 1) {
                File(parent, fileName)
            } else {
                File(parent, "$stem-$index.png")
            }
            add(output)
            add(File(output.absolutePath + ".part"))
        }
        addAll(stableDiffusionPreviewCandidates(File(parent, fileName)))
    }.distinctBy(File::getAbsolutePath)
}

internal fun cleanupStableDiffusionRequestOutputs(outputFile: File) {
    stableDiffusionRequestOutputCandidates(outputFile).forEach { candidate ->
        runCatching { candidate.delete() }
    }
}

internal fun pruneStaleStableDiffusionOutputs(
    outputDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS
) {
    require(maxAgeMillis > 0L) { "stable-diffusion.cpp stale output age must be positive." }
    outputDirectory.listFiles().orEmpty().forEach { candidate ->
        val modifiedAt = candidate.lastModified()
        if (candidate.isFile &&
            STABLE_DIFFUSION_OWNED_OUTPUT_NAME.matches(candidate.name) &&
            modifiedAt > 0L &&
            nowMillis >= modifiedAt &&
            nowMillis - modifiedAt >= maxAgeMillis
        ) {
            runCatching { candidate.delete() }
        }
    }
}

private val MNN_DIFFUSION_OWNED_OUTPUT_NAME = Regex(
    "^mnn-diffusion-(?:[0-9]{1,19}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
        "[0-9a-f]{4}-[0-9a-f]{12})\\.png(?:\\.part)?$"
)
private val QWEN_IMAGE_21_OWNED_OUTPUT_NAME = Regex(
    "^qwen-image-2\\.1-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-" +
        "[0-9a-f]{4}-[0-9a-f]{12}\\.png$"
)

internal fun mnnDiffusionRequestOutputCandidates(outputFile: File): List<File> = listOf(
    outputFile.absoluteFile,
    File(outputFile.absolutePath + ".part")
)

internal fun cleanupMnnDiffusionRequestOutputs(outputFile: File) {
    mnnDiffusionRequestOutputCandidates(outputFile).forEach { candidate ->
        runCatching { candidate.delete() }
    }
}

internal fun pruneStaleMnnDiffusionOutputs(
    outputDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = MNN_DIFFUSION_OUTPUT_MAX_AGE_MS
) {
    require(maxAgeMillis > 0L) { "MNN-Diffusion stale output age must be positive." }
    outputDirectory.listFiles().orEmpty().forEach { candidate ->
        val modifiedAt = candidate.lastModified()
        if (candidate.isFile &&
            MNN_DIFFUSION_OWNED_OUTPUT_NAME.matches(candidate.name) &&
            modifiedAt > 0L &&
            nowMillis >= modifiedAt &&
            nowMillis - modifiedAt >= maxAgeMillis
        ) {
            runCatching { candidate.delete() }
        }
    }
}

internal fun pruneStaleQwenImage21Outputs(
    outputDirectory: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMillis: Long = MNN_DIFFUSION_OUTPUT_MAX_AGE_MS
) {
    require(maxAgeMillis > 0L) { "Qwen-Image-2.1 stale output age must be positive." }
    outputDirectory.listFiles().orEmpty().forEach { candidate ->
        val modifiedAt = candidate.lastModified()
        if (candidate.isFile &&
            QWEN_IMAGE_21_OWNED_OUTPUT_NAME.matches(candidate.name) &&
            modifiedAt > 0L &&
            nowMillis >= modifiedAt &&
            nowMillis - modifiedAt >= maxAgeMillis
        ) {
            runCatching { candidate.delete() }
        }
    }
}

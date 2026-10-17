package com.muyuchat.mca.facade

import java.util.UUID

/**
 * Domain command for image work crossing the Worker boundary.
 *
 * The former admission-only facade was removed because it had no production caller. This command
 * remains the data contract consumed by [com.muyuchat.mca.worker.WorkerCommandAdapter].
 */
data class ImageCommand(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long = -1L,
    val targetOutputPath: String,
    val requestId: String = UUID.randomUUID().toString()
)

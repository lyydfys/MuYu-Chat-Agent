package com.muyuchat.mca.worker

import androidx.work.Data
import com.muyuchat.mca.facade.ChatCommand
import com.muyuchat.mca.facade.ImageCommand
import java.util.UUID

/**
 * Metadata extracted from an incoming WorkManager task.
 */
data class WorkerTaskMetadata(
    val taskId: String,
    val sessionGeneration: Long,
    val attemptCount: Int,
    val idempotencyKey: String,
    val requestedAtMs: Long
)

/**
 * Adapts untrusted WorkManager [Data] bundles into validated domain commands.
 *
 * Hard rules:
 * 1. Parameters are strictly validated; missing required fields or out-of-range
 *    values produce an explicit failure result rather than throwing uncaught runtime crashes.
 * 2. Recovers taskId from malformed inputs to preserve execution identity and attribution.
 */
object WorkerCommandAdapter {

    const val KEY_TASK_ID = "task_id"
    const val KEY_SESSION_GENERATION = "session_generation"
    const val KEY_ATTEMPT_COUNT = "attempt_count"
    const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
    const val KEY_REQUESTED_AT = "requested_at"

    const val KEY_PROMPT = "prompt"
    const val KEY_NEGATIVE_PROMPT = "negative_prompt"
    const val KEY_SESSION_ID = "session_id"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_MAX_TOKENS = "max_tokens"

    const val KEY_IMAGE_WIDTH = "image_width"
    const val KEY_IMAGE_HEIGHT = "image_height"
    const val KEY_IMAGE_STEPS = "image_steps"
    const val KEY_IMAGE_CFG = "image_cfg"
    const val KEY_IMAGE_SEED = "image_seed"
    const val KEY_OUTPUT_PATH = "output_path"

    /**
     * Extracts standard worker task metadata.
     */
    fun extractMetadata(data: Data): WorkerTaskMetadata {
        val taskId = data.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: "task-${UUID.randomUUID()}"
        val generation = data.getLong(KEY_SESSION_GENERATION, 1L)
        val attempts = data.getInt(KEY_ATTEMPT_COUNT, 0)
        val idempotency = data.getString(KEY_IDEMPOTENCY_KEY).orEmpty()
        val requestedAt = data.getLong(KEY_REQUESTED_AT, System.currentTimeMillis())

        return WorkerTaskMetadata(
            taskId = taskId,
            sessionGeneration = generation,
            attemptCount = attempts,
            idempotencyKey = idempotency,
            requestedAtMs = requestedAt
        )
    }

    /**
     * Maps WorkManager Data into a validated domain [ChatCommand].
     */
    fun toChatCommand(data: Data): Result<ChatCommand> = runCatching {
        val meta = extractMetadata(data)
        val prompt = data.getString(KEY_PROMPT)
        require(!prompt.isNullOrBlank()) { "Worker ChatCommand requires a non-blank '$KEY_PROMPT'" }

        val sessionId = data.getString(KEY_SESSION_ID) ?: "worker-session"
        val temperature = data.getFloat(KEY_TEMPERATURE, 0.7f).coerceIn(0.0f, 2.0f)
        val maxTokens = data.getInt(KEY_MAX_TOKENS, 2048).coerceIn(1, 8192)

        ChatCommand(
            prompt = prompt,
            sessionId = sessionId,
            temperature = temperature,
            maxTokens = maxTokens,
            requestId = meta.taskId
        )
    }

    /**
     * Maps WorkManager Data into a validated domain [ImageCommand].
     */
    fun toImageCommand(data: Data): Result<ImageCommand> = runCatching {
        val meta = extractMetadata(data)
        val prompt = data.getString(KEY_PROMPT)
        require(!prompt.isNullOrBlank()) { "Worker ImageCommand requires a non-blank '$KEY_PROMPT'" }

        val outputPath = data.getString(KEY_OUTPUT_PATH)
        require(!outputPath.isNullOrBlank()) { "Worker ImageCommand requires a non-blank '$KEY_OUTPUT_PATH'" }

        val width = data.getInt(KEY_IMAGE_WIDTH, 512).coerceIn(128, 2048)
        val height = data.getInt(KEY_IMAGE_HEIGHT, 512).coerceIn(128, 2048)
        val steps = data.getInt(KEY_IMAGE_STEPS, 20).coerceIn(1, 100)
        val cfg = data.getFloat(KEY_IMAGE_CFG, 7.0f).coerceIn(1.0f, 30.0f)
        val seed = data.getLong(KEY_IMAGE_SEED, -1L)
        val negPrompt = data.getString(KEY_NEGATIVE_PROMPT).orEmpty()

        ImageCommand(
            prompt = prompt,
            negativePrompt = negPrompt,
            width = width,
            height = height,
            steps = steps,
            cfgScale = cfg,
            seed = seed,
            targetOutputPath = outputPath,
            requestId = meta.taskId
        )
    }
}

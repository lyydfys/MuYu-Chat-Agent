package com.muyuchat.mca.worker

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.Retryability
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Enterprise sink for WorkManager background execution results.
 *
 * Enforces:
 * 1. Result persistence without writing or touching any UI StateFlow.
 * 2. Deterministic SHA-256 calculation for artifact files.
 * 3. Exact terminal status reporting with identity attribution.
 * 4. Resilient retry determination matching the domain [Retryability] contract.
 */
object WorkerResultSink {

    const val RESULT_STATUS = "result_status"
    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_FAILURE = "FAILURE"

    const val RESULT_TASK_ID = "result_task_id"
    const val RESULT_RUNTIME_EPOCH = "result_runtime_epoch"
    const val RESULT_OUTPUT_PATH = "result_output_path"
    const val RESULT_FILE_SHA256 = "result_file_sha256"
    const val RESULT_FILE_SIZE = "result_file_size"

    const val RESULT_ERROR_CODE = "result_error_code"
    const val RESULT_ERROR_CATEGORY = "result_error_category"
    const val RESULT_ERROR_MESSAGE = "result_error_message"
    const val RESULT_RECOVERABLE = "result_recoverable"
    const val RESULT_COMPLETED_AT = "result_completed_at"

    /**
     * Produces a terminal success Result with optional file checksum verification.
     */
    fun emitSuccess(
        identity: RequestIdentity,
        outputFile: File? = null,
        extraData: Map<String, Any> = emptyMap()
    ): Result {
        val builder = Data.Builder()
            .putString(RESULT_STATUS, STATUS_SUCCESS)
            .putString(RESULT_TASK_ID, identity.requestId)
            .putLong(RESULT_RUNTIME_EPOCH, identity.runtimeEpoch)
            .putLong(RESULT_COMPLETED_AT, System.currentTimeMillis())

        if (outputFile != null && outputFile.exists()) {
            builder.putString(RESULT_OUTPUT_PATH, outputFile.absolutePath)
            builder.putLong(RESULT_FILE_SIZE, outputFile.length())
            val sha256 = computeSha256(outputFile)
            builder.putString(RESULT_FILE_SHA256, sha256)
        }

        extraData.forEach { (key, value) ->
            when (value) {
                is String -> builder.putString(key, value)
                is Int -> builder.putInt(key, value)
                is Long -> builder.putLong(key, value)
                is Float -> builder.putFloat(key, value)
                is Boolean -> builder.putBoolean(key, value)
            }
        }

        return Result.success(builder.build())
    }

    /**
     * Produces a failure or retry Result depending on the domain error's retryability and current attempts.
     */
    fun emitFailure(
        identity: RequestIdentity?,
        error: ErrorEnvelope,
        currentAttempt: Int = 0,
        maxRetries: Int = 3
    ): Result {
        val isRetryable = (error.retryability == Retryability.SafeRetry || error.retryability == Retryability.RetryAfterRecovery) &&
            currentAttempt < maxRetries

        val outputData = Data.Builder()
            .putString(RESULT_STATUS, STATUS_FAILURE)
            .putString(RESULT_TASK_ID, identity?.requestId ?: "unknown")
            .putLong(RESULT_RUNTIME_EPOCH, identity?.runtimeEpoch ?: 0L)
            .putString(RESULT_ERROR_CODE, error.code.name)
            .putString(RESULT_ERROR_CATEGORY, error.category.name)
            .putString(RESULT_ERROR_MESSAGE, error.publicMessage)
            .putBoolean(RESULT_RECOVERABLE, isRetryable)
            .putLong(RESULT_COMPLETED_AT, System.currentTimeMillis())
            .build()

        return if (isRetryable) {
            Result.retry()
        } else {
            Result.failure(outputData)
        }
    }

    /**
     * Computes the SHA-256 hash of a file safely.
     */
    fun computeSha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}

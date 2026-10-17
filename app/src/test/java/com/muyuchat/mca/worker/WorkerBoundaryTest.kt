package com.muyuchat.mca.worker

import androidx.work.Data
import androidx.work.ListenableWorker
import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.Retryability
import com.muyuchat.core.engine.contract.error.StableErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkerBoundaryTest {

    @Test
    fun `WorkerCommandAdapter extracts metadata with fallback taskId`() {
        val emptyData = Data.Builder().build()
        val meta = WorkerCommandAdapter.extractMetadata(emptyData)
        assertTrue(meta.taskId.startsWith("task-"))
        assertEquals(1L, meta.sessionGeneration)
        assertEquals(0, meta.attemptCount)
        assertEquals("", meta.idempotencyKey)

        val populatedData = Data.Builder()
            .putString(WorkerCommandAdapter.KEY_TASK_ID, "task-custom-123")
            .putLong(WorkerCommandAdapter.KEY_SESSION_GENERATION, 42L)
            .putInt(WorkerCommandAdapter.KEY_ATTEMPT_COUNT, 2)
            .putString(WorkerCommandAdapter.KEY_IDEMPOTENCY_KEY, "idemp-abc")
            .build()
        val metaPopulated = WorkerCommandAdapter.extractMetadata(populatedData)
        assertEquals("task-custom-123", metaPopulated.taskId)
        assertEquals(42L, metaPopulated.sessionGeneration)
        assertEquals(2, metaPopulated.attemptCount)
        assertEquals("idemp-abc", metaPopulated.idempotencyKey)
    }

    @Test
    fun `WorkerCommandAdapter maps valid ChatCommand and rejects missing prompt`() {
        val validData = Data.Builder()
            .putString(WorkerCommandAdapter.KEY_PROMPT, "Analyze this dataset")
            .putFloat(WorkerCommandAdapter.KEY_TEMPERATURE, 0.5f)
            .putInt(WorkerCommandAdapter.KEY_MAX_TOKENS, 512)
            .build()
        val chatResult = WorkerCommandAdapter.toChatCommand(validData)
        assertTrue(chatResult.isSuccess)
        val cmd = chatResult.getOrThrow()
        assertEquals("Analyze this dataset", cmd.prompt)
        assertEquals(0.5f, cmd.temperature, 0.01f)
        assertEquals(512, cmd.maxTokens)

        val invalidData = Data.Builder().build()
        val failResult = WorkerCommandAdapter.toChatCommand(invalidData)
        assertTrue(failResult.isFailure)
    }

    @Test
    fun `WorkerCommandAdapter maps valid ImageCommand and rejects missing output path`() {
        val validData = Data.Builder()
            .putString(WorkerCommandAdapter.KEY_PROMPT, "Cyberpunk landscape")
            .putString(WorkerCommandAdapter.KEY_OUTPUT_PATH, "/data/local/tmp/output.png")
            .putInt(WorkerCommandAdapter.KEY_IMAGE_WIDTH, 1024)
            .putInt(WorkerCommandAdapter.KEY_IMAGE_HEIGHT, 1024)
            .build()
        val imgResult = WorkerCommandAdapter.toImageCommand(validData)
        assertTrue(imgResult.isSuccess)
        val cmd = imgResult.getOrThrow()
        assertEquals("Cyberpunk landscape", cmd.prompt)
        assertEquals("/data/local/tmp/output.png", cmd.targetOutputPath)
        assertEquals(1024, cmd.width)

        val invalidData = Data.Builder()
            .putString(WorkerCommandAdapter.KEY_PROMPT, "No output path")
            .build()
        val failResult = WorkerCommandAdapter.toImageCommand(invalidData)
        assertTrue(failResult.isFailure)
    }

    @Test
    fun `WorkerResultSink emits success with file checksum verification`() {
        val tempFile = File.createTempFile("worker_test_", ".bin").apply {
            writeBytes("test file content for sha256 verification".toByteArray())
            deleteOnExit()
        }

        val identity = RequestIdentity(requestId = "job-456", runtimeEpoch = 2L)
        val result = WorkerResultSink.emitSuccess(
            identity = identity,
            outputFile = tempFile,
            extraData = mapOf("processed_items" to 10)
        )

        assertTrue(result is ListenableWorker.Result.Success)
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(WorkerResultSink.STATUS_SUCCESS, outputData.getString(WorkerResultSink.RESULT_STATUS))
        assertEquals("job-456", outputData.getString(WorkerResultSink.RESULT_TASK_ID))
        assertEquals(2L, outputData.getLong(WorkerResultSink.RESULT_RUNTIME_EPOCH, 0L))
        assertEquals(tempFile.absolutePath, outputData.getString(WorkerResultSink.RESULT_OUTPUT_PATH))
        assertEquals(tempFile.length(), outputData.getLong(WorkerResultSink.RESULT_FILE_SIZE, 0L))
        val sha256 = outputData.getString(WorkerResultSink.RESULT_FILE_SHA256)
        assertNotNull(sha256)
        assertTrue(sha256!!.isNotEmpty())
    }

    @Test
    fun `WorkerResultSink respects Retryability semantics`() {
        val identity = RequestIdentity(requestId = "job-789", runtimeEpoch = 1L)

        // SafeRetry error should yield Result.retry() when attempts < maxRetries
        val retryableError = ErrorEnvelope(
            code = StableErrorCode.RT_CRASHED,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Execute,
            retryability = Retryability.SafeRetry,
            userMessageKey = "retryable",
            publicMessage = "Temporary crash"
        )
        val retryResult = WorkerResultSink.emitFailure(identity, retryableError, currentAttempt = 1, maxRetries = 3)
        assertTrue(retryResult is ListenableWorker.Result.Retry)

        // Permanent failure should yield Result.failure()
        val permanentError = ErrorEnvelope(
            code = StableErrorCode.VAL_INVALID_FORMAT,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Load,
            retryability = Retryability.Never,
            userMessageKey = "permanent",
            publicMessage = "Invalid model weights"
        )
        val failResult = WorkerResultSink.emitFailure(identity, permanentError, currentAttempt = 1, maxRetries = 3)
        assertTrue(failResult is ListenableWorker.Result.Failure)
    }
}

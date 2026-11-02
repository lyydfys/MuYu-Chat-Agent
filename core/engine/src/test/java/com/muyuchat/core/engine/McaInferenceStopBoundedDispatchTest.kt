package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact-token stop dispatch must stay bounded even when the runner's `requestStop()` blocks
 * behind the same native lock as an in-flight prefill/decode.
 *
 * Device evidence for the bug: the worker's synchronous `beginCompletion` Binder transaction
 * (AIDL code 4) blocked for 245232 ms, so any stop that waits for the runner's native return can
 * not be the mechanism that breaks a blocked prefill. These tests use a runner whose
 * `requestStop()` blocks for up to 30 s; without the bounded dispatch the calling coroutine would
 * block until the runner returned (the JUnit timeout fails the test).
 */
class McaInferenceStopBoundedDispatchTest {
    @Test(timeout = 15_000)
    fun blockedNativeStopIsDispatchedWithoutHoldingTheExactTokenCaller() = runBlocking {
        val runner = BlockingStopRunner()
        val service = serviceWith(runner)
        val token = service.activateGenerationStopTargetImpl("req-blocked", runner, Job())
        try {
            val startedAtMs = System.nanoTime()
            val accepted = service.stopGenerationIfActive(token)
            val elapsedMs = (System.nanoTime() - startedAtMs) / 1_000_000L
            assertTrue("the exact-token stop must be accepted", accepted)
            assertTrue(
                "engine stop returned after ${elapsedMs}ms; it must stay inside the bounded window",
                elapsedMs < 3_000L
            )
            assertEquals("the native stop must be dispatched exactly once", 1, runner.stopCalls.get())
        } finally {
            runner.release.countDown()
            service.clearGenerationStopTargetImpl(token)
        }
    }

    @Test(timeout = 15_000)
    fun duplicateStopForTheSameTokenDoesNotDispatchAnotherNativeStop() = runBlocking {
        val runner = BlockingStopRunner()
        val service = serviceWith(runner)
        val token = service.activateGenerationStopTargetImpl("req-duplicate", runner, Job())
        try {
            assertTrue(service.stopGenerationIfActive(token))
            assertTrue("a duplicate stop is still accepted for the same token", service.stopGenerationIfActive(token))
            assertEquals(1, runner.stopCalls.get())
        } finally {
            runner.release.countDown()
            service.clearGenerationStopTargetImpl(token)
        }
    }

    @Test
    fun staleTokenNeverReachesTheReplacementRequest() = runBlocking {
        val runner = BlockingStopRunner()
        val service = serviceWith(runner)
        val token = service.activateGenerationStopTargetImpl("req-current", runner, Job())
        try {
            val stale = GenerationStopToken(epoch = token.epoch + 100L, requestId = "req-stale")
            assertFalse("a stop for another generation must be a no-op", service.stopGenerationIfActive(stale))
            assertEquals(0, runner.stopCalls.get())
            assertFalse(service.stopGenerationIfActive(null))
            assertEquals(0, runner.stopCalls.get())
        } finally {
            runner.release.countDown()
            service.clearGenerationStopTargetImpl(token)
        }
    }

    @Test(timeout = 15_000)
    fun runnerCancellationIsRethrownInsteadOfReportedAsAccepted() = runBlocking {
        val runner = ThrowingStopRunner(CancellationException("native stop was cancelled"))
        val result = runCatching { dispatchRunnerStopBounded(runner, 1_000L) }
        assertTrue(
            "a CancellationException must not be swallowed into a successful stop",
            result.exceptionOrNull() is CancellationException
        )
    }

    @Test(timeout = 15_000)
    fun aRunnerStopFailureIsReportedAsNotDispatched() = runBlocking {
        val runner = ThrowingStopRunner(IllegalStateException("native stop wedged"))
        assertFalse(dispatchRunnerStopBounded(runner, 1_000L))
    }

    @Test(timeout = 15_000)
    fun aStopThatIsStillBlockedAfterTheWindowIsAcceptedAsDispatched() = runBlocking {
        val runner = BlockingStopRunner()
        try {
            val startedAtMs = System.nanoTime()
            assertTrue(dispatchRunnerStopBounded(runner, STOP_REQUEST_TIMEOUT_MS))
            val elapsedMs = (System.nanoTime() - startedAtMs) / 1_000_000L
            assertTrue("bounded dispatch overran: ${elapsedMs}ms", elapsedMs < 3_000L)
            assertEquals(1, runner.stopCalls.get())
        } finally {
            runner.release.countDown()
        }
    }

    private fun serviceWith(runner: LocalChatRunner): McaInferenceService =
        McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )

    private open class BaseRunner : LocalChatRunner {
        override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        override fun initBackends(nativeLibDir: String) = Unit
        override fun loadModel(modelPath: String, paramsJson: String): Int = 0
        override fun unloadModel() = Unit
        override fun beginCompletion(messagesJson: String, paramsJson: String): Int = 0
        override fun generateNextChunk(): String? = null
        override fun requestStop() = Unit
        override fun getRuntimeStatsJson(): String = "{}"
        override fun shutdown() = Unit
    }

    private class BlockingStopRunner : BaseRunner() {
        val stopCalls = AtomicInteger(0)
        val release = CountDownLatch(1)

        override fun requestStop() {
            stopCalls.incrementAndGet()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    private class ThrowingStopRunner(private val error: Throwable) : BaseRunner() {
        override fun requestStop() {
            throw error
        }
    }

    private class FakeContext : ContextWrapper(null) {
        private val root = File(
            System.getProperty("java.io.tmpdir"),
            "mca-bounded-stop-test-${System.nanoTime()}"
        )
        private val files = File(root, "files").also { it.mkdirs() }
        private val cache = File(root, "cache").also { it.mkdirs() }
        private val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(root, "lib").also { it.mkdirs() }.absolutePath
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getApplicationInfo(): ApplicationInfo = appInfo
    }
}

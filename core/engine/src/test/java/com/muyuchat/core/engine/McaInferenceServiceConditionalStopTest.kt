package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McaInferenceServiceConditionalStopTest {
    @Test
    fun serviceReturnsTheRunnerAtomicStopDecision() = runBlocking {
        val runner = ConditionalStopRunner(accepted = true)
        val service = serviceWith(runner)

        assertTrue(service.stopGenerationIfActive())
        assertEquals(1, runner.conditionalStopCalls)
    }

    @Test
    fun unsupportedRunnerFailsClosedInsteadOfClaimingCancellation() = runBlocking {
        val runner = UnsupportedConditionalStopRunner()
        val service = serviceWith(runner)

        assertFalse(service.stopGenerationIfActive())
        assertEquals(0, runner.unconditionalStopCalls)
    }

    private fun serviceWith(runner: LocalChatRunner): McaInferenceService =
        McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner)
        )

    private open class BaseRunner : LocalChatRunner {
        var unconditionalStopCalls = 0

        override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        override fun initBackends(nativeLibDir: String) = Unit
        override fun loadModel(modelPath: String, paramsJson: String): Int = 0
        override fun unloadModel() = Unit
        override fun beginCompletion(messagesJson: String, paramsJson: String): Int = 0
        override fun generateNextChunk(): String? = null
        override fun requestStop() {
            unconditionalStopCalls += 1
        }

        override fun getRuntimeStatsJson(): String = "{}"
        override fun shutdown() = Unit
    }

    private class ConditionalStopRunner(
        private val accepted: Boolean
    ) : BaseRunner() {
        var conditionalStopCalls = 0

        override fun requestStopIfActive(): Boolean {
            conditionalStopCalls += 1
            return accepted
        }
    }

    private class UnsupportedConditionalStopRunner : BaseRunner()

    private class FakeContext : ContextWrapper(null) {
        private val root = File(
            System.getProperty("java.io.tmpdir"),
            "mca-conditional-stop-test-${System.nanoTime()}"
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

package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import com.muyuchat.core.telemetry.MemorySnapshot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QairtIsolatedDryRunAdmissionTest {
    @Test
    fun unknownExactIdentityRequiresRealIsolatedCanaryBeforeNormalLoad() = runBlocking {
        val bundle = qairtBundle()
        val identity = QairtBundleRuntimeIdentity(
            bundleSha256 = "qwen3-vl-4b-exact-bundle",
            chipset = "SM8750P",
            runtimeFingerprint = "geniex-qairt/test-runtime"
        )
        val qairtRunner = FakeRunner(LocalChatRuntime.GENIEX_QAIRT)
        val service = service(
            qairtRunner = qairtRunner,
            identity = identity,
            isolatedProcess = true,
            verificationFile = File(bundle, "verifications.json")
        )

        val normalAttempt = service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 1024, nThreads = 4),
            qairtBundleSha256 = identity.bundleSha256
        )
        assertTrue(normalAttempt.isFailure)
        assertTrue(normalAttempt.exceptionOrNull() is QairtIsolatedDryRunRequiredException)
        assertEquals(0, qairtRunner.loadCalls)

        service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 1024, nThreads = 4),
            qairtBundleSha256 = identity.bundleSha256,
            qairtExecutionPurpose = QairtExecutionPurpose.ISOLATED_DRY_RUN
        ).getOrThrow()
        assertEquals(1, qairtRunner.loadCalls)
        // A handle alone never certifies the package.
        assertFalse(service.recordVerifiedQairtDryRun(identity.bundleSha256))

        qairtRunner.enqueue("蓝色圆形和红色方形")
        val events = service.streamChat(
            ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "请描述图片")),
                params = GenerationParams(nCtx = 1024, nPredict = 8, nThreads = 4)
            )
        ).toList()
        assertTrue(events.any { it is GenerateEvent.Done })
        // A visible answer without a clean destroy is still insufficient.
        assertFalse(service.recordVerifiedQairtDryRun(identity.bundleSha256))

        service.unloadModel()
        assertTrue(service.recordVerifiedQairtDryRun(identity.bundleSha256))

        service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 1024, nThreads = 4),
            qairtBundleSha256 = identity.bundleSha256
        ).getOrThrow()
        assertEquals(2, qairtRunner.loadCalls)
    }

    @Test
    fun isolatedPurposeCannotBeUsedFromTheNormalAppProcess() = runBlocking {
        val bundle = qairtBundle()
        val identity = QairtBundleRuntimeIdentity("bundle", "SM8750P", "runtime")
        val runner = FakeRunner(LocalChatRuntime.GENIEX_QAIRT)
        val service = service(
            qairtRunner = runner,
            identity = identity,
            isolatedProcess = false,
            verificationFile = File(bundle, "verifications.json")
        )

        val result = service.loadModel(
            modelPath = bundle.absolutePath,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            params = LoadParams(nCtx = 1024, nThreads = 4),
            qairtBundleSha256 = identity.bundleSha256,
            qairtExecutionPurpose = QairtExecutionPurpose.ISOLATED_DRY_RUN
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(":qairt_smoke"))
        assertEquals(0, runner.loadCalls)
    }

    private fun service(
        qairtRunner: FakeRunner,
        identity: QairtBundleRuntimeIdentity,
        isolatedProcess: Boolean,
        verificationFile: File
    ): McaInferenceService = McaInferenceService(
        context = FakeContext(),
        runners = mapOf(
            LocalChatRuntime.MNN_CPU to FakeRunner(LocalChatRuntime.MNN_CPU),
            LocalChatRuntime.GENIEX_QAIRT to qairtRunner
        ),
        memorySnapshotProvider = {
            MemorySnapshot(totalMemKb = 16L * 1024L * 1024L, availMemKb = 8L * 1024L * 1024L)
        },
        qairtVerificationStoreOverride = QairtExecutionVerificationStore(verificationFile),
        qairtIdentityProviderOverride = { requested -> identity.takeIf { it.bundleSha256 == requested } },
        qairtDryRunProcessVerifierOverride = { isolatedProcess }
    )

    private fun qairtBundle(): File = Files.createTempDirectory("qairt-isolated-dry-run").toFile().also { bundle ->
        File(bundle, "metadata.json").writeText(
            """
            {
              "model_name": "Qwen3-VL-4B-Instruct",
              "model_files": {
                "part.bin": {
                  "inputs": {
                    "past_key_0_in": {"shape": [1, 1, 128, 1023], "dtype": "uint8"},
                    "past_value_0_in": {"shape": [1, 1, 1023, 128], "dtype": "uint8"}
                  }
                }
              }
            }
            """.trimIndent()
        )
    }

    private class FakeContext : ContextWrapper(null) {
        private val root = File(System.getProperty("java.io.tmpdir"), "qairt-isolated-${System.nanoTime()}")
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

    private class FakeRunner(override val runtime: LocalChatRuntime) : LocalChatRunner {
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null
        var loadCalls = 0
        private var loaded = false
        private val chunks = ArrayDeque<String>()

        override fun initBackends(nativeLibDir: String) = Unit

        override fun loadModel(modelPath: String, paramsJson: String): Int {
            loadCalls += 1
            loaded = true
            return 0
        }

        override fun unloadModel() {
            loaded = false
        }

        override fun beginCompletion(messagesJson: String, paramsJson: String): Int = 0

        override fun generateNextChunk(): String? =
            if (chunks.isEmpty()) null else chunks.removeFirst()

        override fun requestStop() = Unit

        override fun getRuntimeStatsJson(): String = JSONObject()
            .put("backend", runtime.backendId)
            .put("loaded", loaded)
            .put("runnerReady", true)
            .put("visionReady", runtime == LocalChatRuntime.GENIEX_QAIRT)
            .put(
                "backendDevices",
                if (runtime == LocalChatRuntime.GENIEX_QAIRT) "QAIRT NPU" else "CPU"
            )
            .put("lastError", "")
            .toString()

        override fun shutdown() {
            unloadModel()
        }

        fun enqueue(value: String) {
            chunks.clear()
            chunks.addLast(value)
        }
    }
}

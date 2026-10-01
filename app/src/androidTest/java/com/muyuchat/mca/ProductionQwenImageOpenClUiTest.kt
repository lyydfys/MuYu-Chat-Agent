package com.muyuchat.mca

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires a real installed Qwen-Image-2.1 MNN bundle. There are no fake outputs,
 * direct worker calls, injected ViewModel actions, or device admission lists.
 * Run methods separately: the success case is the only complete 20-step run;
 * the cancellation case retries at Tiny 1:1, two steps to bound validation cost.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class ProductionQwenImageOpenClUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var main: MainViewModel
    private lateinit var device: UiDevice
    private lateinit var diagnosticFile: File
    private lateinit var diagnostic: JSONObject
    private var ownedJobId: String? = null
    private var originalPrompt: String? = null
    private var selectedModelId: String? = null

    @Test
    fun productionImagePageGenerates512x512TwentyStepsOnOpenCl() = withDiagnostics("success-512-20") {
        openImagePageAndSelectQwen()
        configureRequest(width = 512, height = 512, steps = 20, seed = 1729)
        val job = submitNewJob(512, 512, 20, 1729, SUCCESS_PROMPT)
        val terminal = awaitTerminal(job.id, GENERATION_TIMEOUT_MS)
        assertEquals("Generation failed: ${terminal.message}", ImageGenerationStatusRecord.DONE, terminal.status)
        verifyNewOutput(terminal, width = 512, height = 512, steps = 20, seed = 1729)
    }

    @Test
    fun cancellationDuringNativeExecutionRecoversWithNewOpenClRequest() = withDiagnostics("cancel-recovery") {
        openImagePageAndSelectQwen()
        configureRequest(width = 512, height = 512, steps = 20, seed = 1729)
        val cancelledJob = submitNewJob(512, 512, 20, 1729, CANCEL_PROMPT)
        await("native denoising start", GENERATION_TIMEOUT_MS) {
            val current = currentJob(cancelledJob.id)
            assertFalse("Task failed before cancellation: ${current.message}", current.status.failed)
            assertFalse("Task finished before cancellation was exercised", current.status.terminal)
            // Published only after the real native load returned READY and the provider
            // entered the generation operation; cancelling a queued task is insufficient.
            current.message.contains("正在生成图片")
        }
        SystemClock.sleep(2_000L)
        diagnostic.put("cancellationBefore", jobJson(currentJob(cancelledJob.id)))
        cancelThroughVisibleUi()
        val cancelled = awaitTerminal(cancelledJob.id, CANCELLATION_TIMEOUT_MS)
        assertEquals("Cancellation failed: ${cancelled.message}", ImageGenerationStatusRecord.CANCELLED, cancelled.status)
        assertTrue("Cancelled task must not publish a final asset", outputIds(cancelled).isEmpty())
        assertFalse("Production Activity must survive native cancellation", compose.activity.isFinishing)
        diagnostic.put("cancelledJob", jobJson(cancelled))
        saveDiagnostics("cancelled")

        // Reconfigure through the same page. A new request must load and execute
        // again; accepting an old image or just a READY state does not prove recovery.
        compose.waitUntil(30_000L) {
            compose.onNodeWithTag("image.generate").runCatching { fetchSemanticsNode() }.isSuccess
        }
        configureRequest(width = 320, height = 320, steps = 2, seed = 1730)
        val retry = submitNewJob(320, 320, 2, 1730, RECOVERY_PROMPT)
        assertFalse("Recovery must be a distinct request", cancelledJob.id == retry.id)
        val terminal = awaitTerminal(retry.id, GENERATION_TIMEOUT_MS)
        assertEquals("Recovery failed: ${terminal.message}", ImageGenerationStatusRecord.DONE, terminal.status)
        verifyNewOutput(terminal, width = 320, height = 320, steps = 2, seed = 1730)
        assertEquals("Later retry must not rewrite cancelled history", ImageGenerationStatusRecord.CANCELLED,
            currentJob(cancelledJob.id).status)
        diagnostic.put("recoveryJob", jobJson(terminal))
    }

    private fun openImagePageAndSelectQwen() {
        compose.waitUntil(60_000L) {
            compose.onNodeWithTag("chat.editor").runCatching { fetchSemanticsNode() }.isSuccess
        }
        // Obtain the Activity's existing startup owner. The state is read solely
        // for evidence; all selection, submission and cancellation use actual UI.
        compose.runOnUiThread {
            main = requireNotNull(ViewModelProvider(compose.activity)[AppStartupViewModel::class.java].state.value.model)
        }
        assertTrue("Another live image task must finish before this isolated test",
            main.uiState.value.imageJobs.none { !it.status.terminal })
        val candidates = main.uiState.value.localImageModels.filter { model ->
            model.runtime == LocalImageRuntime.MNN_DIFFUSION &&
                (model.recommendationId == "qwen_image_21_mnn_opencl" ||
                    (model.displayName.contains("Qwen-Image-2.1", ignoreCase = true) &&
                        model.displayName.contains("OpenCL", ignoreCase = true)))
        }
        assertEquals("Exactly one installed Qwen-Image-2.1 MNN OpenCL record is required; found ${candidates.map { it.displayName }}",
            1, candidates.size)
        val model = candidates.single()
        assertTrue("Required Qwen model files are missing: ${model.path}", model.configured)
        selectedModelId = model.id
        diagnostic.put("model", JSONObject().put("id", model.id).put("name", model.displayName)
            .put("runtime", model.runtime.name).put("primaryPath", model.path).put("sha256", model.sha256))
        compose.onNodeWithContentDescription("打开历史").performClick()
        compose.onNodeWithText("图片").performClick()
        compose.waitUntil(20_000L) {
            compose.onNodeWithTag("image.prompt.editor").runCatching { fetchSemanticsNode() }.isSuccess
        }
        originalPrompt = compose.onNodeWithTag("image.prompt.editor")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        if (main.uiState.value.selectedImageBackend == ImageBackend.CLOUD) {
            compose.onNodeWithText("云端生图").performClick()
            compose.onNodeWithText("本地生图").performClick()
        } else {
            compose.onNodeWithTag("image.model.selector").performClick()
        }
        // The popup appears after the underlying selector in the semantics tree.
        // Select the popup item even if the same name is already in that selector.
        val items = compose.onAllNodesWithText(model.displayName)
        val count = items.fetchSemanticsNodes().size
        assertTrue("Installed Qwen model must be offered by the real UI", count > 0)
        items[count - 1].performClick()
        await("UI model selection", 15_000L) {
            main.uiState.value.selectedImageBackend == ImageBackend.LOCAL &&
                main.uiState.value.selectedLocalImageModelId == model.id
        }
        saveDiagnostics("image-page-ready")
    }

    private fun configureRequest(width: Int, height: Int, steps: Int, seed: Int) {
        if (compose.onNodeWithTag("image.steps").runCatching { fetchSemanticsNode() }.isFailure) {
            compose.onNodeWithText("更多参数").performScrollTo().performClick()
        }
        replaceParameter("image.width", width.toString())
        replaceParameter("image.height", height.toString())
        replaceParameter("image.steps", steps.toString())
        replaceParameter("image.seed", seed.toString())
        // Persisted user settings can have batch > 1. Explicitly select one before
        // starting an expensive native run. This is the final numeric chip row.
        if (compose.onNodeWithText("输出张数").runCatching { fetchSemanticsNode() }.isSuccess) {
            compose.onNodeWithText("输出张数").performScrollTo()
            val ones = compose.onAllNodes(hasText("1") and hasClickAction())
            val count = ones.fetchSemanticsNodes().size
            assertTrue("Single-image output chip is missing", count > 0)
            ones[count - 1].performClick()
        }
        diagnostic.put("configured", JSONObject().put("width", width).put("height", height)
            .put("steps", steps).put("seed", seed).put("batchCount", 1))
        saveDiagnostics("parameters-configured")
    }

    private fun replaceParameter(tag: String, value: String) {
        val field = compose.onNodeWithTag(tag)
        field.performScrollTo().performTextReplacement(value)
        assertEquals("UI field $tag did not retain the requested value", value,
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun submitNewJob(width: Int, height: Int, steps: Int, seed: Int, prompt: String): ImageGenerationJobRecord {
        val previousJobs = main.uiState.value.imageJobs.map { it.id }.toSet()
        val previousImages = main.uiState.value.images.map { it.id }.toSet()
        compose.onNodeWithTag("image.prompt.editor").performTextReplacement(prompt)
        val submittedAt = System.currentTimeMillis()
        diagnostic.put("baselineImageIds", JSONArray(previousImages.toList()))
        diagnostic.put("submittedAtMillis", submittedAt)
        diagnostic.put("requestPrompt", prompt)
        compose.onNodeWithTag("image.generate").performClick()
        await("new image job created by UI", 30_000L) {
            main.uiState.value.imageJobs.any { it.id !in previousJobs }
        }
        val newJobs = main.uiState.value.imageJobs.filter { it.id !in previousJobs }
        assertEquals("A single click must create exactly one new task", 1, newJobs.size)
        val job = newJobs.single()
        ownedJobId = job.id
        diagnostic.put("submittedJob", jobJson(job))
        saveDiagnostics("job-submitted")
        assertEquals(ImageBackend.LOCAL, job.backend)
        assertEquals(selectedModelId, job.modelId)
        assertEquals(prompt, job.prompt)
        assertTrue("New task must be bound to this submission", job.startedAtMillis >= submittedAt - 2_000L)
        val spec = requireNotNull(job.spec) { "UI task lacks a captured generation request" }
        assertEquals(width, spec.options.width)
        assertEquals(height, spec.options.height)
        assertEquals(steps, spec.options.steps)
        assertEquals(seed, spec.options.seed)
        assertEquals("Test must not accidentally launch a persisted batch", 1, spec.options.batchCount)
        return job
    }

    private fun awaitTerminal(jobId: String, timeoutMs: Long): ImageGenerationJobRecord {
        await("terminal image task $jobId", timeoutMs) { currentJob(jobId).status.terminal }
        return currentJob(jobId).also {
            diagnostic.put("terminalJob", jobJson(it))
            saveDiagnostics("task-terminal")
        }
    }

    private fun verifyNewOutput(job: ImageGenerationJobRecord, width: Int, height: Int, steps: Int, seed: Int) {
        val ids = outputIds(job)
        assertEquals("Batch=1 must publish exactly one output", 1, ids.size)
        val prior = diagnostic.getJSONArray("baselineImageIds")
        val previousIds = (0 until prior.length()).map { prior.getString(it) }.toSet()
        assertFalse("Historical output must not satisfy this generation", ids.single() in previousIds)
        val assets = main.uiState.value.images.filter { it.id in ids }
        assertEquals("Completed task must reference a committed image-library asset", 1, assets.size)
        val asset = assets.single()
        assertEquals(width, asset.width)
        assertEquals(height, asset.height)
        assertTrue("Completed image must have nonempty saved metadata", asset.generationMetadataJson.isNotBlank())
        val history = JSONObject(asset.generationMetadataJson)
        assertEquals(selectedModelId, history.getString("modelId"))
        assertEquals(job.prompt, history.getString("requestPrompt"))
        val options = history.getJSONObject("options")
        assertEquals(width, options.getInt("width"))
        assertEquals(height, options.getInt("height"))
        assertEquals(steps, options.getInt("steps"))
        assertEquals(seed, options.getInt("seed"))
        val audit = history.getJSONObject("nativeExecution")
        val native = audit.getJSONObject("nativeEffective")
        for (proof in listOf(audit, native)) {
            assertTrue("Real native generation must complete", proof.getBoolean("nativeRunCompleted"))
            assertTrue("Native runtime must confirm execution backend", proof.getBoolean("backendExecutionConfirmedByNative"))
            assertTrue(proof.getBoolean("nativeExecution"))
            assertFalse("CPU fallback must not count as OpenCL success", proof.getBoolean("fallback"))
            assertEquals("MNN_OPENCL", proof.getString("effectiveBackend"))
            assertEquals("MNN_DIFFUSION", proof.getString("runtime"))
            assertEquals(width, proof.getInt("width"))
            assertEquals(height, proof.getInt("height"))
            assertEquals(steps, proof.getInt("steps"))
            assertEquals(seed, proof.getInt("seed"))
            assertTrue(proof.getLong("nativeGenerationSequence") > 0L)
        }
        assertEquals("MNN_OPENCL", audit.getString("backendConfigured"))
        assertEquals("mnn_runtime_resolution_after_native_run", audit.getString("backendExecutionProof"))
        assertTrue(audit.getInt("nativeGenerationCount") > 0)
        assertEquals(audit.getLong("nativeGenerationSequence"), native.getLong("nativeGenerationSequence"))
        val bytes = requireNotNull(compose.activity.contentResolver.openInputStream(Uri.parse(asset.uriString)))
            .use { it.readBytes() }
        assertTrue("Saved file is not a PNG", bytes.size > PNG_SIGNATURE.size &&
            bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Saved PNG must decode", bitmap)
        requireNotNull(bitmap).let {
            try {
                assertEquals(width, it.width)
                assertEquals(height, it.height)
            } finally {
                it.recycle()
            }
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Audit must bind to this exact saved PNG", sha256, audit.getString("outputSha256"))
        assertEquals(sha256, native.getString("outputSha256"))
        assertEquals(bytes.size.toLong(), asset.sizeBytes)
        assertEquals(bytes.size.toLong(), audit.getLong("outputBytes"))
        assertEquals(bytes.size.toLong(), native.getLong("outputBytes"))
        assertFalse("Production Activity must survive real inference", compose.activity.isFinishing)
        diagnostic.put("output", JSONObject().put("id", asset.id).put("uri", asset.uriString)
            .put("bytes", bytes.size).put("sha256", sha256).put("width", width).put("height", height))
        diagnostic.put("nativeExecution", audit)
        saveDiagnostics("output-verified")
    }

    private fun cancelThroughVisibleUi() {
        // UiAutomator clicks the actual visible Compose control without asking
        // Compose to globally idle while native generation and progress run.
        val button = device.wait(Until.findObject(By.desc("停止生成")), 10_000L)
            ?: device.wait(Until.findObject(By.text("取消生成")), 10_000L)
            ?: throw AssertionError("No visible production cancellation control")
        button.click()
    }

    private fun currentJob(id: String): ImageGenerationJobRecord =
        requireNotNull(main.uiState.value.imageJobs.firstOrNull { it.id == id }) { "Current task disappeared: $id" }

    private fun outputIds(job: ImageGenerationJobRecord): List<String> =
        job.imageAssetIds.ifEmpty { job.imageAssetId?.let(::listOf).orEmpty() }

    private fun await(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var nextReportAt = SystemClock.elapsedRealtime() + 20_000L
        while (!condition()) {
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) throw AssertionError("Timed out waiting for $label; ${stateSnapshot()}")
            if (now >= nextReportAt) {
                saveDiagnostics("waiting: $label")
                nextReportAt = now + 20_000L
            }
            SystemClock.sleep(250L)
        }
    }

    private fun jobJson(job: ImageGenerationJobRecord): JSONObject = JSONObject()
        .put("id", job.id).put("status", job.status.name).put("modelId", job.modelId)
        .put("message", job.message).put("startedAtMillis", job.startedAtMillis)
        .put("previewStep", job.previewStep).put("imageAssetIds", JSONArray(outputIds(job)))

    private fun stateSnapshot(): JSONObject = JSONObject().apply {
        if (::main.isInitialized) {
            val state = main.uiState.value
            put("statusMessage", state.statusMessage)
            put("selectedImageBackend", state.selectedImageBackend.name)
            put("selectedModelId", state.selectedLocalImageModelId)
            put("jobs", JSONArray().apply { state.imageJobs.take(8).forEach { put(jobJson(it)) } })
            put("imageCount", state.images.size)
        }
    }

    private fun packageVersionName(): String = compose.activity.packageManager
        .getPackageInfo(compose.activity.packageName, 0).versionName.orEmpty()

    private fun packageVersionCode(): Long {
        val info = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }
    private fun saveDiagnostics(stage: String) {
        diagnostic.put("stage", stage).put("updatedAtMillis", System.currentTimeMillis())
            .put("state", stateSnapshot())
        diagnosticFile.writeText(diagnostic.toString(2))
    }

    private fun withDiagnostics(name: String, block: () -> Unit) {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val configurator = Configurator.getInstance()
        val oldIdleTimeout = configurator.waitForIdleTimeout
        configurator.setWaitForIdleTimeout(0L)
        diagnosticFile = File(compose.activity.filesDir, "diagnostics/repair-qwen-opencl-ui-$name.json").apply {
            parentFile?.mkdirs()
        }
        val started = SystemClock.elapsedRealtime()
        diagnostic = JSONObject().put("test", name).put("runId", UUID.randomUUID().toString())
            .put("startedAtMillis", System.currentTimeMillis()).put("apkVersion", packageVersionName())
            .put("apkVersionCode", packageVersionCode()).put("deviceModel", Build.MODEL)
            .put("deviceHardware", Build.HARDWARE).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        saveDiagnostics("START")
        var failed: Throwable? = null
        try {
            block()
            diagnostic.put("result", "PASS").put("elapsedMs", SystemClock.elapsedRealtime() - started)
            saveDiagnostics("PASS")
            device.takeScreenshot(File(diagnosticFile.parentFile, "repair-qwen-opencl-ui-$name.png"))
        } catch (error: Throwable) {
            failed = error
            diagnostic.put("result", "FAIL").put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("failure", error.stackTraceToString())
            saveDiagnostics("FAIL")
            runCatching { device.takeScreenshot(File(diagnosticFile.parentFile, "repair-qwen-opencl-ui-$name.png")) }
            throw error
        } finally {
            try {
                val active = if (::main.isInitialized) ownedJobId?.let { id ->
                    main.uiState.value.imageJobs.firstOrNull { it.id == id && !it.status.terminal }
                } else null
                if (active != null) {
                    runCatching {
                        cancelThroughVisibleUi()
                        awaitTerminal(active.id, CANCELLATION_TIMEOUT_MS)
                    }.onFailure { cleanup ->
                        diagnostic.put("cleanupFailure", cleanup.stackTraceToString())
                        saveDiagnostics(if (failed == null) "cleanup-failed" else "FAIL")
                        failed?.addSuppressed(cleanup)
                    }
                }
                // Preserve the user's prompt draft using the same real editor.
                if (::main.isInitialized && main.uiState.value.imageJobs.none { !it.status.terminal }) {
                    originalPrompt?.let { original ->
                        runCatching { compose.onNodeWithTag("image.prompt.editor").performTextReplacement(original) }
                            .onFailure { diagnostic.put("draftRestoreFailure", it.stackTraceToString()); saveDiagnostics("draft-restore-failed") }
                    }
                }
            } finally {
                configurator.setWaitForIdleTimeout(oldIdleTimeout)
            }
        }
    }

    companion object {
        private const val GENERATION_TIMEOUT_MS = 30L * 60L * 1_000L
        private const val CANCELLATION_TIMEOUT_MS = 3L * 60L * 1_000L
        private const val SUCCESS_PROMPT = "A blue bird sitting on a branch at sunrise, soft natural light."
        private const val CANCEL_PROMPT = "A red ceramic cup on a wooden table, soft studio light."
        private const val RECOVERY_PROMPT = "A yellow flower in a glass vase, daylight, simple composition."
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
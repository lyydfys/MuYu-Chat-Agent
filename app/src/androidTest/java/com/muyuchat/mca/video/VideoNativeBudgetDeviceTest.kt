package com.muyuchat.mca.video

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.video.media.VideoMp4Verifier
import com.muyuchat.mca.video.model.VideoModelComponentRole
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.mca.video.worker.VideoWorkerCore
import com.muyuchat.mca.video.worker.VideoWorkerPipeline
import com.muyuchat.mca.video.workflow.VideoWorkflowCompiler
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in headless native budget/quality probe. This is NOT product acceptance and NOT the
 * production worker path: it invokes the real [VideoWorkerPipeline] in the instrumentation
 * process so a locked device can still produce native/MP4/frame evidence. Enable explicitly:
 *
 *   adb shell am instrument -w -e videoBudgetProbe true -e width 128 -e height 128 \
 *     -e frames 9 -e steps 4 com.muyuchat.mca.devicetest.test/\
 *     androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class VideoNativeBudgetDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 3_600_000)
    fun nativeBudgetProbe() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "enable with -e videoBudgetProbe true",
            args.getString("videoBudgetProbe") == "true",
        )
        val width = args.getString("width")?.toIntOrNull() ?: 128
        val height = args.getString("height")?.toIntOrNull() ?: 128
        val frames = args.getString("frames")?.toIntOrNull() ?: 9
        val steps = args.getString("steps")?.toIntOrNull() ?: 4
        val prompt = args.getString("prompt")
            ?: "a red ball bouncing on a wooden table, cinematic light"
        // Diagnostic-only: production never sets this, so the native default (true) is unchanged.
        val flashAttn = args.getString("flashAttn")?.toBooleanStrictOrNull() ?: true
        // Diagnostic-only VAE tiling knobs; only forwarded when supplied, so native defaults stay unchanged.
        val vaeTiling = args.getString("vaeTiling")?.toBooleanStrictOrNull()
        val tileSizeX = args.getString("tileSizeX")?.toIntOrNull()
        val tileSizeY = args.getString("tileSizeY")?.toIntOrNull()
        val tileOverlap = args.getString("tileOverlap")?.toFloatOrNull()
        // Diagnostic-only LoRA knobs; forwarded only when supplied so production/default stays LoRA-free.
        val loraPath = args.getString("loraPath")
        val loraWeight = args.getString("loraWeight")?.toFloatOrNull()
        val manifest = Wan22Ti2v5bProfiles.Q4_0_Q8_0
        val modelRoot = File(context.filesDir, "video_models/wan2.2-ti2v-5b")
        assumeTrue(
            "pinned model bundle not installed",
            manifest.components.all { File(modelRoot, it.relativePath).isFile },
        )

        val stagingRoot = File(context.filesDir, "video/runs").apply { mkdirs() }
        val runDir = File(stagingRoot, "headless-${System.currentTimeMillis()}").apply { mkdirs() }
        val modelFilesByRole = manifest.components.associateBy { it.role }
        val payload = JSONObject().apply {
            put("runDir", runDir.absolutePath)
            put("stagingRoot", stagingRoot.absolutePath)
            put("modelRoot", modelRoot.absolutePath)
            put("modelFingerprint", VideoWorkflowCompiler.modelFingerprintOf(manifest))
            put(
                "ditPath",
                File(modelRoot, modelFilesByRole.getValue(VideoModelComponentRole.DIT).relativePath).absolutePath,
            )
            put(
                "encoderPath",
                File(modelRoot, modelFilesByRole.getValue(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL).relativePath).absolutePath,
            )
            put(
                "vaePath",
                File(modelRoot, modelFilesByRole.getValue(VideoModelComponentRole.VAE_WAN22_5B).relativePath).absolutePath,
            )
            put("prompt", prompt)
            put("negativePrompt", "")
            put("imagePath", "")
            put("width", width)
            put("height", height)
            put("frames", frames)
            put("fps", 16)
            put("steps", steps)
            put("seed", 42L)
            put("guidance", 6.0)
            put("flowShift", 3.0)
            put("sampler", "euler")
            put("threads", 4)
            put("flashAttn", flashAttn)
            // Optional 4-step distillation LoRA; forwarded only when both args are supplied.
            args.getString("loraPath")?.takeIf { it.isNotBlank() }?.let { path ->
                put("loraPath", path)
                put("loraWeight", args.getString("loraWeight")?.toFloatOrNull() ?: 1.0f)
            }
            vaeTiling?.let { put("vaeTiling", it) }
            tileSizeX?.let { put("tileSizeX", it) }
            tileSizeY?.let { put("tileSizeY", it) }
            tileOverlap?.let { put("tileOverlap", it.toDouble()) }
            loraPath?.let { put("loraPath", it) }
            loraWeight?.let { put("loraWeight", it.toDouble()) }
        }
        val startReport = File(context.filesDir, "video-budget-${width}x$height-${steps}steps-start.txt")
        startReport.writeText(
            "probe=native_budget_not_product_acceptance\nresolved=${width}x${height}x$frames steps=$steps\nprompt=$prompt\nflashAttn=$flashAttn\nvaeTiling=${vaeTiling ?: "unset"} tileSizeX=${tileSizeX ?: ""} tileSizeY=${tileSizeY ?: ""} tileOverlap=${tileOverlap ?: ""}\nlora=${loraPath ?: "off"}@${loraWeight ?: ""}\nrunDir=${runDir.absolutePath}\n",
            Charsets.UTF_8,
        )

        val stageLog = File(context.filesDir, "video-budget-${width}x$height-${steps}steps-stages.txt")
        stageLog.writeText("", Charsets.UTF_8)
        val stages = java.util.Collections.synchronizedList(mutableListOf<String>())
        var outcome: VideoWorkerCore.WorkerOutcome? = null
        val execution = object : VideoWorkerCore.Execution {
            override val runId = "headless-run"
            override val attemptId = "headless-attempt"
            override val requestId = "headless-request"
            override val payloadJson = payload.toString()

            override fun cancelRequested(): Boolean = false

            override fun reportExecutionDetail(detail: String) {
                val line = "${SystemClock.elapsedRealtime()} detail $detail"
                stages += line
                runCatching {
                    synchronized(stageLog) { stageLog.appendText(line + "\n", Charsets.UTF_8) }
                }
            }

            override fun reportStage(stage: VideoRunStage, step: Long?, stepTotal: Long?) {
                val line = "${SystemClock.elapsedRealtime()} $stage step=$step total=$stepTotal"
                stages += line
                runCatching {
                    synchronized(stageLog) { stageLog.appendText(line + "\n", Charsets.UTF_8) }
                }
            }

            override fun complete(workerOutcome: VideoWorkerCore.WorkerOutcome) {
                outcome = workerOutcome
            }
        }

        val running = AtomicBoolean(true)
        val peakPssKb = AtomicLong(0L)
        val sampler = thread(name = "budget-pss") {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            while (running.get()) {
                val pss = runCatching {
                    am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: 0
                }.getOrDefault(0)
                peakPssKb.updateAndGet { maxOf(it, pss.toLong()) }
                Thread.sleep(5_000)
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            runBlocking { VideoWorkerPipeline(context).execute(execution) }
        } finally {
            running.set(false)
            sampler.join(2_000)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val output = File(runDir, "output.mp4")
        val verification = if (output.isFile) {
            VideoMp4Verifier().verify(output, width, height, frames, 16)
        } else {
            null
        }
        val frameFiles = extractFrames(output, runDir)
        val report = buildString {
            appendLine("probe=native_budget_not_product_acceptance")
            appendLine("width=$width")
            appendLine("height=$height")
            appendLine("frames=$frames")
            appendLine("steps=$steps")
            appendLine("fps=16")
            appendLine("prompt=$prompt")
            appendLine("lora=${loraPath ?: "off"}@${loraWeight ?: ""}")
            appendLine("outcome=$outcome")
            appendLine("elapsedMs=$elapsedMs")
            appendLine("peakPssKb=${peakPssKb.get()}")
            appendLine("stages=${synchronized(stages) { stages.toList() }.joinToString("|")}")
            appendLine("media=${output.absolutePath}")
            appendLine("mediaBytes=${if (output.isFile) output.length() else 0L}")
            appendLine("mediaSha256=${if (output.isFile) sha256(output) else ""}")
            appendLine("verification=$verification")
            appendLine("framesExtracted=${frameFiles.joinToString(",") { it.name }}")
        }
        File(context.filesDir, "video-budget-${width}x$height-${steps}steps-report.txt")
            .writeText(report, Charsets.UTF_8)
        Log.i("MCTAVideo", report)

        assertTrue("probe did not succeed; report written\n$report", outcome is VideoWorkerCore.WorkerOutcome.Succeeded)
        assertTrue("MP4 verification failed\n$report", verification is VideoMp4Verifier.Result.Verified)
    }

    private fun extractFrames(output: File, runDir: File): List<File> {
        if (!output.isFile) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(output.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val targets = listOf(0L, durationMs / 2L, (durationMs - 1L).coerceAtLeast(0L))
            targets.mapIndexedNotNull { index, atMs ->
                val bitmap = retriever.getFrameAtTime(
                    atMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@mapIndexedNotNull null
                val file = File(runDir, "probe-frame-$index.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                file
            }
        } finally {
            retriever.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

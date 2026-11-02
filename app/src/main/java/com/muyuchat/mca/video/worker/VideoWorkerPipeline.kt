package com.muyuchat.mca.video.worker

import android.content.Context
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.video.codec.VideoMp4Encoder
import com.muyuchat.mca.video.model.ComponentVerification
import com.muyuchat.mca.video.model.VideoModelAssetVerifier
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.mca.video.workflow.VideoWorkflowCompiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Physical execution body of the :local_video worker (V1-03 + V1-04).
 *
 * Chain: verify pinned model roles/sizes → fused native generate_video (real sampler progress) →
 * decode-complete marker → real MediaCodec/MediaMuxer H.264 encode of that same spool → succeed
 * with the staged MP4. The worker never writes Room state and never publishes; the process owner
 * verifies, publishes and settles.
 *
 * Cancellation: the cooperative flag flips the DSO cancel callback; the native call must RETURN
 * before this body reports Stopped. A decode or codec phase that cannot return is escalated by the
 * worker/owner watchdog to process death, never reported as "released".
 */
class VideoWorkerPipeline(private val context: Context) {

    private companion object {
        const val DECODE_HEARTBEAT_MS = 15_000L
    }

    private val bridge = NativeStableDiffusionBridge()

    suspend fun execute(execution: VideoWorkerCore.Execution) {
        val payload = runCatching { ExecutionPayload.parse(execution.payloadJson) }.getOrElse {
            execution.complete(VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.INPUT_INVALID, "执行参数无法解析"))
            return
        }
        execution.reportStage(VideoRunStage.PREPARE_INPUTS)
        val manifest = Wan22Ti2v5bProfiles.Q4_0_Q4_K_M
        val verification = withContext(Dispatchers.IO) {
            VideoModelAssetVerifier.verify(
                manifest = manifest,
                bundleRoot = payload.modelRoot,
                hashFile = { VideoModelAssetVerifier.sha256Of(it) },
            )
        }
        val modelProblem = modelProblemOf(verification)
        if (modelProblem != null) {
            execution.complete(VideoWorkerCore.WorkerOutcome.Failed(modelProblem.first, modelProblem.second))
            return
        }
        if (VideoWorkflowCompiler.modelFingerprintOf(manifest) != payload.modelFingerprint) {
            execution.complete(
                VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.MODEL_ROLE_MISMATCH, "模型清单指纹与冻结快照不一致"),
            )
            return
        }
        if (execution.cancelRequested()) {
            execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
            return
        }

        execution.reportStage(VideoRunStage.LOAD_MODEL)
        execution.reportStage(VideoRunStage.TEXT_ENCODE)
        // External runs push every decoded frame straight into the encoder: the native call
        // blocks until the codec takes the frame, so generation cannot outrun encoding and no
        // frame is ever spooled to disk.
        val externalRequest = runCatching {
            JSONObject(execution.payloadJson).optString("engine").isNotEmpty()
        }.getOrDefault(false)
        var pushMp4: VideoMp4Encoder? = null
        var pushConsumer: NativeStableDiffusionBridge.VideoFrameConsumer? = null
        // The full Wan VAE is only survivable at 480p when spatially tiled, and the engine refuses
        // the push sink while spatial tiling is on ("cannot stream temporal tiles"). That mode
        // therefore writes frames and encodes afterwards; TAE keeps the streaming path.
        val fullVaeDecode = runCatching {
            com.muyuchat.mca.video.settings.VideoEnginePreferenceStore(context).isFullVaeEnabled()
        }.getOrDefault(false)
        if (externalRequest && !fullVaeDecode) {
            val candidate = VideoMp4Encoder(isCancelled = execution::cancelRequested)
            val opened = candidate.open(
                width = payload.width,
                height = payload.height,
                fps = payload.fps,
                output = File(payload.runDirectory, "output.mp4"),
            )
            if (opened != null) {
                val startupFailure = opened as? VideoMp4Encoder.Result.Failed
                candidate.abort()
                execution.complete(
                    VideoWorkerCore.WorkerOutcome.Failed(
                        startupFailure?.code ?: VideoErrorCode.ENCODER_UNAVAILABLE,
                        startupFailure?.detail ?: "编码器未能启动",
                    ),
                )
                return
            }
            pushMp4 = candidate
            pushConsumer = NativeStableDiffusionBridge.VideoFrameConsumer { _, rgb -> candidate.submit(rgb) }
        }
        val nativeJson = coroutineScope {
            val poll = launch(Dispatchers.IO) {
                var lastStep = -1L
                var samplingDone = false
                var lastDecodeBeat = 0L
                val requestedSteps = payload.steps
                while (isActive && !execution.cancelRequested()) {
                    val progress = runCatching { bridge.getVideoProgress() }.getOrNull()
                    if (progress != null) {
                        val parsed = runCatching { JSONObject(progress) }.getOrNull()
                        if (parsed != null) {
                            // sd.cpp shares one progress channel across text encoding and sampling;
                            // only the frozen sampler total is real SAMPLE progress.
                            val step = VideoProgressProjection.samplerStep(
                                phase = parsed.optString("phase"),
                                step = parsed.optLong("step", -1L),
                                total = parsed.optLong("steps", 0L),
                                requestedSteps = requestedSteps,
                            )
                            if (step != null && step != lastStep) {
                                lastStep = step
                                execution.reportStage(VideoRunStage.SAMPLE, step, requestedSteps.toLong())
                            }
                            if (step != null && step >= requestedSteps) samplingDone = true
                            // A tiled full-VAE decode emits nothing for tens of minutes. Without a
                            // heartbeat the run looks stalled to both the page and the worker's own
                            // stall guard, so report the real phase while the native call is alive.
                            if (samplingDone) {
                                val now = System.currentTimeMillis()
                                if (now - lastDecodeBeat > DECODE_HEARTBEAT_MS) {
                                    lastDecodeBeat = now
                                    execution.reportStage(VideoRunStage.DECODE, null, null)
                                }
                            }
                        }
                    }
                    delay(400)
                }
                if (execution.cancelRequested()) {
                    runCatching { bridge.cancelVideo() }
                }
            }
            if (execution.cancelRequested()) {
                runCatching { bridge.cancelVideo() }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val consumer = pushConsumer
                    if (consumer != null) {
                        bridge.generateVideoStreaming(execution.payloadJson, consumer)
                    } else {
                        bridge.generateVideo(execution.payloadJson)
                    }
                }.getOrElse { "{\"status\":\"failed\",\"code\":\"NATIVE_EXECUTION_FAILED\",\"detail\":\"${it.javaClass.simpleName}\"}" }
            }
            poll.cancelAndJoin()
            result
        }

        val native = runCatching { JSONObject(nativeJson) }.getOrElse {
            execution.complete(VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.NATIVE_EXECUTION_FAILED, "native 返回了不可解析结果"))
            return
        }
        when (native.optString("status")) {
            "cancelled" -> {
                pushMp4?.abort()
                deleteRecursively(payload.runDirectory)
                execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
                return
            }

            "failed" -> {
                val code = runCatching { VideoErrorCode.valueOf(native.optString("code")) }
                    .getOrDefault(VideoErrorCode.NATIVE_EXECUTION_FAILED)
                pushMp4?.abort()
                execution.complete(VideoWorkerCore.WorkerOutcome.Failed(code, native.optString("detail").takeIf { it.isNotEmpty() }))
                return
            }

            "succeeded" -> Unit
            else -> {
                execution.complete(VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.NATIVE_EXECUTION_FAILED, "native 状态未知"))
                return
            }
        }

        val frameCount = native.optInt("frameCount", 0)
        val frameNames = native.optJSONArray("frames")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }.orEmpty()
        if (frameCount != payload.frames || frameNames.size != frameCount) {
            execution.complete(VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "帧数与冻结参数不一致"))
            return
        }
        val effective = native.optJSONObject("effective")
        if (effective == null) {
            execution.complete(
                VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.NATIVE_EXECUTION_FAILED, "native 未回显有效参数"),
            )
            return
        }
        val effectiveMismatch = effectiveMismatch(effective, payload)
        if (effectiveMismatch != null) {
            execution.complete(
                VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.NATIVE_EXECUTION_FAILED, effectiveMismatch),
            )
            return
        }
        File(payload.runDirectory, "native-effective.json").writeText(effective.toString(), Charsets.UTF_8)
        // Report which external backend actually ran (htp, or cpu after the native fallback). The
        // owner relays this diagnostic with the success so the Local API can echo the real value
        // instead of the requested one.
        val actualExternalBackend = effective.optString("externalBackend")
        if (actualExternalBackend.isNotBlank()) {
            execution.reportExecutionDetail(
                JSONObject()
                    .put("externalBackend", actualExternalBackend)
                    .put("fallback", effective.optString("externalBackendFallback"))
                    .put("reason", effective.optString("externalBackendReason"))
                    .toString(),
            )
        }
        if (execution.cancelRequested()) {
            deleteRecursively(payload.runDirectory)
            execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
            return
        }
        // The native call has fully returned: latent→RGB decoding is complete. This is a
        // completion marker for the real phase, not a fabricated progress percentage.
        execution.reportStage(VideoRunStage.DECODE, frameCount.toLong(), frameCount.toLong())
        execution.reportStage(VideoRunStage.ENCODE)
        val frameDir = File(payload.runDirectory, "frames")
        val output = File(payload.runDirectory, "output.mp4")
        when (
            val encoded = pushMp4?.close() ?: withContext(Dispatchers.IO) {
                VideoMp4Encoder(isCancelled = execution::cancelRequested).encode(
                    frameDir = frameDir,
                    frameNames = frameNames,
                    width = payload.width,
                    height = payload.height,
                    fps = payload.fps,
                    output = output,
                )
            }
        ) {
            is VideoMp4Encoder.Result.Encoded -> {
                if (execution.cancelRequested()) {
                    output.delete()
                    deleteRecursively(payload.runDirectory)
                    execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
                } else {
                    deleteRecursively(frameDir)
                    execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
                }
            }

            VideoMp4Encoder.Result.Cancelled -> {
                output.delete()
                deleteRecursively(payload.runDirectory)
                execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
            }

            is VideoMp4Encoder.Result.Failed -> {
                execution.complete(VideoWorkerCore.WorkerOutcome.Failed(encoded.code, encoded.detail))
            }
        }
    }

    private fun effectiveMismatch(effective: JSONObject, payload: ExecutionPayload): String? {
        fun checkLong(name: String, requested: Long): String? {
            val actual = effective.optLong(name, Long.MIN_VALUE)
            return if (actual == requested) null else "native 参数 $name=$actual 与冻结值 $requested 不一致"
        }
        checkLong("width", payload.width.toLong())?.let { return it }
        checkLong("height", payload.height.toLong())?.let { return it }
        checkLong("frames", payload.frames.toLong())?.let { return it }
        checkLong("fps", payload.fps.toLong())?.let { return it }
        checkLong("steps", payload.steps.toLong())?.let { return it }
        checkLong("seed", payload.seed)?.let { return it }
        val guidance = effective.optDouble("guidance", Double.NaN)
        if (!guidance.isFinite() || kotlin.math.abs(guidance - payload.guidance) > 0.0005) {
            return "native guidance=$guidance 与冻结值 ${payload.guidance} 不一致"
        }
        val flow = effective.optDouble("flowShift", Double.NaN)
        if (!flow.isFinite() || kotlin.math.abs(flow - payload.flowShift) > 0.0005) {
            return "native flowShift=$flow 与冻结值 ${payload.flowShift} 不一致"
        }
        if (effective.optString("sampler") != payload.sampler) {
            return "native sampler=${effective.optString("sampler")} 与冻结值 ${payload.sampler} 不一致"
        }
        return null
    }

    private fun modelProblemOf(verification: com.muyuchat.mca.video.model.VideoModelAssetVerification): Pair<VideoErrorCode, String>? {
        if (verification is com.muyuchat.mca.video.model.VideoModelAssetVerification.Ready) return null
        verification as com.muyuchat.mca.video.model.VideoModelAssetVerification.Problems
        if (verification.insufficientSpaceBytes != null) {
            return VideoErrorCode.DISK_FULL to "模型与暂存空间不足"
        }
        verification.components.forEach { outcome ->
            when (outcome) {
                is ComponentVerification.Missing ->
                    return VideoErrorCode.MODEL_MISSING to "缺少模型组件 ${outcome.file.role.name}"

                is ComponentVerification.SizeMismatch ->
                    return outcome.code to "模型组件 ${outcome.file.role.name} 大小不符"

                is ComponentVerification.ChecksumMismatch ->
                    return VideoErrorCode.MODEL_CHECKSUM_MISMATCH to "模型组件 ${outcome.file.role.name} 校验和不符"

                is ComponentVerification.SizeOnlyOk, is ComponentVerification.Ok -> Unit
            }
        }
        return VideoErrorCode.MODEL_MISSING to "模型组件不完整"
    }

    private fun deleteRecursively(file: File) {
        runCatching { if (file.exists()) file.deleteRecursively() }
    }

    private data class ExecutionPayload(
        val runDirectory: File,
        val stagingRoot: File,
        val modelRoot: File,
        val modelFingerprint: String,
        val prompt: String,
        val negativePrompt: String,
        val imagePath: String,
        val width: Int,
        val height: Int,
        val frames: Int,
        val fps: Int,
        val steps: Int,
        val seed: Long,
        val guidance: Double,
        val flowShift: Double,
        val sampler: String,
        val threads: Int,
    ) {
        companion object {
            fun parse(json: String): ExecutionPayload {
                val root = JSONObject(json)
                val runDirectory = File(root.getString("runDir")).canonicalFile
                val stagingRoot = File(root.getString("stagingRoot")).canonicalFile
                require(runDirectory.path.startsWith(stagingRoot.path + File.separator)) {
                    "run directory escaped staging root"
                }
                require(runDirectory.isDirectory) { "run directory is missing" }
                return ExecutionPayload(
                    runDirectory = runDirectory,
                    stagingRoot = stagingRoot,
                    modelRoot = File(root.getString("modelRoot")).canonicalFile,
                    modelFingerprint = root.getString("modelFingerprint"),
                    prompt = root.getString("prompt"),
                    negativePrompt = root.optString("negativePrompt"),
                    imagePath = root.optString("imagePath"),
                    width = root.getInt("width"),
                    height = root.getInt("height"),
                    frames = root.getInt("frames"),
                    fps = root.getInt("fps"),
                    steps = root.getInt("steps"),
                    seed = root.getLong("seed"),
                    guidance = root.getDouble("guidance"),
                    flowShift = root.getDouble("flowShift"),
                    sampler = root.getString("sampler"),
                    threads = root.optInt("threads", 4),
                )
            }
        }
    }
}

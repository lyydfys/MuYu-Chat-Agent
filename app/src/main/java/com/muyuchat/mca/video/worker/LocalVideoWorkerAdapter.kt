package com.muyuchat.mca.video.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoRequestId
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.ILocalVideoWorker
import com.muyuchat.mca.video.settings.VideoEnginePreferenceStore
import com.muyuchat.mca.ILocalVideoWorkerCallback
import com.muyuchat.mca.video.data.VideoSnapshotCodec
import com.muyuchat.mca.video.execution.VideoExecutionOutcome
import com.muyuchat.mca.video.execution.VideoExecutionRequest
import com.muyuchat.mca.video.execution.VideoGenerationOwner
import com.muyuchat.mca.video.media.VideoMediaPublisher
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Owner-side adapter for the disposable :local_video worker (V1-02b protocol + V1-03 execution).
 *
 * Only the worker process touches JNI: this adapter stages typed inputs (no frames/MP4 over
 * Binder), binds the service, forwards real stage/outcome callbacks and maps them onto the owner's
 * suspend contract. One attempt runs at a time; additional attempts wait for the single worker
 * slot and remain PREPARING instead of starting a second runtime. Binder death is INTERRUPTED
 * (stop unconfirmed), never "released".
 */
class LocalVideoWorkerAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val publisher: VideoMediaPublisher,
    private val acquireRuntimeLease: (suspend () -> com.muyuchat.core.engine.EngineLifecycleLeaseHandle?)? = null,
    private val modelRoot: File = File(context.filesDir, "video_models/${Wan22Ti2v5bProfiles.MODEL_ID}"),
) : VideoGenerationOwner.VideoExecutionAdapter {

    private val semaphore = Semaphore(1)
    private val active = ConcurrentHashMap<String, ILocalVideoWorker>()
    private val cancelledWhileWaiting = ConcurrentHashMap.newKeySet<String>()

    override suspend fun execute(
        request: VideoExecutionRequest,
        reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
    ): VideoExecutionOutcome {
        return semaphore.withPermit {
            if (cancelledWhileWaiting.remove(request.requestId.value)) {
                return@withPermit VideoExecutionOutcome.Stopped
            }
            val lease = try {
                acquireRuntimeLease?.invoke()
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                return@withPermit VideoExecutionOutcome.Failed(
                    VideoErrorCode.BACKEND_UNAVAILABLE,
                "无法获取聊天生命周期租约：${failure.javaClass.simpleName}",
                )
            } ?: return@withPermit VideoExecutionOutcome.Failed(
                VideoErrorCode.BACKEND_UNAVAILABLE,
                "视频生命周期租约未装配，拒绝在未仲裁的资源上执行",
            )
            try {
                if (cancelledWhileWaiting.remove(request.requestId.value)) {
                    return@withPermit VideoExecutionOutcome.Stopped
                }
                try {
                    runAttempt(request, reportStage)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    VideoExecutionOutcome.Failed(
                        VideoErrorCode.NATIVE_EXECUTION_FAILED,
                    "视频执行适配失败：${failure.javaClass.simpleName}",
                    )
                }
            } finally {
                cancelledWhileWaiting.remove(request.requestId.value)
                runCatching { lease.release() }
            }
        }
    }

    override fun cancel(requestId: VideoRequestId) {
        cancelledWhileWaiting.add(requestId.value)
        val binder = active[requestId.value] ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { binder.cancel(requestId.value) }
        }
    }

    private suspend fun runAttempt(
        request: VideoExecutionRequest,
        reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
    ): VideoExecutionOutcome {
        if (cancelledWhileWaiting.remove(request.requestId.value)) {
            return VideoExecutionOutcome.Stopped
        }
        val modelFiles = modelFilePaths()
        modelFiles.firstOrNull { !it.isFile }?.let { missing ->
            return VideoExecutionOutcome.Failed(
                VideoErrorCode.MODEL_MISSING,
                "缺少基础 TI2V-5B 组件：${missing.name}（模型目录 ${modelRoot.absolutePath}）",
            )
        }
        // Decode quality is a device-local preference read at execution time: full Wan VAE with
        // spatial tiling (~32 min per 480p clip) instead of the TAE head (~6 min).
        val fullVaeDecode = runCatching {
            VideoEnginePreferenceStore(context).isFullVaeEnabled()
        }.getOrDefault(false)
        android.util.Log.i("McaVideoWorker", "fullVae=$fullVaeDecode")
        val runDir = publisher.ensureRunDirectory(request.runId.value)
        // Native accepts auxiliary decoder weights only as a regular file inside the run staging
        // directory (it resolves the path and refuses anything outside), so the TAE head is copied
        // in rather than referenced from the model root.
        val stagedTae = withContext(Dispatchers.IO) {
            val source = File(modelRoot, "vae/taew2_2.safetensors")
            val target = File(runDir, "tae.safetensors")
            if (!source.isFile) {
                null
            } else if (target.isFile && target.length() == source.length()) {
                target
            } else {
                runCatching {
                    source.copyTo(target, overwrite = true)
                    target
                }.getOrNull()
            }
        }
        // Native resolves every auxiliary weight file and refuses anything outside the run staging
        // directory, so LoRA files are linked (or copied) in before the payload is built. A hard
        // link keeps the 300+ MB asset single-instance and survives the run-directory cleanup.
        val requestedLoras = request.snapshot.resolvedParams.loraSpecs
        val stagedLoras = withContext(Dispatchers.IO) {
            requestedLoras.mapIndexedNotNull { index, spec ->
                val source = File(spec.path)
                if (!source.isFile) return@mapIndexedNotNull null
                val target = File(runDir, "lora_$index.safetensors")
                if (!(target.isFile && target.length() == source.length())) {
                    target.delete()
                    val linked = runCatching {
                        java.nio.file.Files.createLink(target.toPath(), source.toPath())
                    }.isSuccess
                    if (!linked) {
                        runCatching { source.copyTo(target, overwrite = true) }
                    }
                }
                target.takeIf { it.isFile }?.let {
                    com.muyuchat.core.video.contract.VideoLoraBinding(it.absolutePath, spec.weight)
                }
            }
        }
        android.util.Log.i(
            "McaVideoWorker",
            "lora requested=${requestedLoras.size} staged=${stagedLoras.size}",
        )
        if (stagedLoras.size != requestedLoras.size) {
            return VideoExecutionOutcome.Failed(
                VideoErrorCode.INPUT_INVALID,
                "LoRA 权重无法暂存到运行目录（${requestedLoras.size - stagedLoras.size} 个失败）",
            )
        }
        val imageBinding = request.snapshot.resolvedParams.imageRef
        val stagedImage = if (imageBinding != null) {
            withContext(Dispatchers.IO) { stageInputImage(imageBinding, runDir) }
                ?: return VideoExecutionOutcome.Failed(
                    VideoErrorCode.INPUT_MISSING,
                    "输入图片无法解析为受控暂存文件",
                )
        } else {
            null
        }
        val prompt = promptPair(request)
        // The snapshot only stores the prompt hash (privacy), while execution reads the text from
        // the workflow document. If the two disagree (an edited draft or a retry that keeps an
        // older document), the worker would run different text than the approved snapshot while
        // recording the snapshot's hash. Refuse instead of running the wrong prompt silently.
        if (!promptMatchesSnapshot(prompt.first, request.snapshot.resolvedParams.promptSha256)) {
            return VideoExecutionOutcome.Failed(
                VideoErrorCode.INPUT_INVALID,
                "提示词与提交时的快照不一致，已拒绝执行；请重新提交该修订",
            )
        }
        val manifest = Wan22Ti2v5bProfiles.Q4_0_Q4_K_M
        val modelFilesByRole = manifest.components.associateBy { it.role }
        val payload = videoNativeRequestPayload(
            runDir = runDir.absolutePath,
            stagingRoot = File(context.filesDir, "video/runs").absolutePath,
            modelRoot = modelRoot.absolutePath,
            modelFingerprint = request.snapshot.modelFingerprint,
            ditPath = File(
                modelRoot,
                modelFilesByRole.getValue(com.muyuchat.mca.video.model.VideoModelComponentRole.DIT).relativePath,
            ).absolutePath,
            encoderPath = File(
                modelRoot,
                modelFilesByRole.getValue(com.muyuchat.mca.video.model.VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL).relativePath,
            ).absolutePath,
            // The TAE head only exists on the external engine; the native layer rejects a taePath
            // on the built-in path ("taePath/vaeProfile require engine=sdcpp_external").
            taePath = if (request.snapshot.resolvedParams.engine.isNotBlank() && !fullVaeDecode) {
                stagedTae?.absolutePath.orEmpty()
            } else {
                ""
            },
            fullVaeDecode = fullVaeDecode,
            vaePath = File(
                modelRoot,
                modelFilesByRole.getValue(com.muyuchat.mca.video.model.VideoModelComponentRole.VAE_WAN22_5B).relativePath,
            ).absolutePath,
            prompt = prompt.first,
            negativePrompt = prompt.second,
            imagePath = stagedImage?.absolutePath.orEmpty(),
            params = request.snapshot.resolvedParams.copy(loraSpecs = stagedLoras),
        )
        val snapshotJson = VideoSnapshotCodec.encode(request.snapshot)
        val events = Channel<Event>(Channel.UNLIMITED)
        val connected = CompletableDeferred<ILocalVideoWorker>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service?.let { ILocalVideoWorker.Stub.asInterface(it) }
                if (binder == null) connected.completeExceptionally(IllegalStateException("empty video worker binder"))
                else connected.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("video worker disconnected"))
                events.trySend(Event.Interrupted("视频 worker 进程已断开，无法确认停止"))
            }

            override fun onBindingDied(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("video worker binding died"))
                events.trySend(Event.Interrupted("视频 worker 绑定失效，无法确认停止"))
            }
        }
        if (cancelledWhileWaiting.remove(request.requestId.value)) {
            // Cancelled between staging and binding: do not create the worker at all.
            return VideoExecutionOutcome.Stopped
        }
        val bound = context.bindService(
            Intent(context, LocalVideoWorkerService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
        return VideoExecutionOutcome.Failed(VideoErrorCode.BACKEND_UNAVAILABLE, "无法绑定视频 worker 服务")
        }
        try {
            val service = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
            ?: return VideoExecutionOutcome.Failed(VideoErrorCode.BACKEND_UNAVAILABLE, "视频 worker 绑定超时")
            active[request.requestId.value] = service
            // Close the race where cancel() ran before this attempt was visible to the binder,
            // and the binder call therefore went nowhere.
            if (cancelledWhileWaiting.remove(request.requestId.value)) {
                return VideoExecutionOutcome.Stopped
            }
            val callback = object : ILocalVideoWorkerCallback.Stub() {
                override fun onStage(stage: String?, step: Long, stepTotal: Long, hasStep: Boolean) {
                    val parsed = stage?.let { runCatching { VideoRunStage.valueOf(it) }.getOrNull() }
                    if (parsed != null) {
                        events.trySend(Event.Stage(parsed, step.takeIf { hasStep }, stepTotal.takeIf { hasStep }))
                    }
                }

                override fun onOutcome(outcomeKind: String?, errorCode: String?, detail: String?) {
                    events.trySend(Event.Outcome(outcomeKind.orEmpty(), errorCode, detail))
                }
            }
            val admitted = runCatching {
                service.begin(
                    request.runId.value,
                    request.attemptId.value,
                    request.requestId.value,
                    snapshotJson,
                    payload.toString(),
                    request.snapshot.backend.name,
                    callback,
                )
            }.getOrElse {
        return VideoExecutionOutcome.Interrupted("启动视频 worker 失败：${it.javaClass.simpleName}")
            }
            if (!admitted) {
                return if (cancelledWhileWaiting.remove(request.requestId.value)) {
                    VideoExecutionOutcome.Stopped
                } else {
        VideoExecutionOutcome.Failed(VideoErrorCode.BACKEND_UNAVAILABLE, "视频 worker 已拒绝（已有活动任务）")
                }
            }
            if (cancelledWhileWaiting.remove(request.requestId.value)) {
                // A cancel landed between the pre-begin check and begin(); the worker is now
                // bound to this request id, so the identity-checked cancel can stop it before
                // the gate is acquired.
                runCatching { service.cancel(request.requestId.value) }
            }
            var lastProgressAt = System.currentTimeMillis()
            while (true) {
                val stallLimit = if (fullVaeDecode) FULL_VAE_PROGRESS_TIMEOUT_MS else PROGRESS_TIMEOUT_MS
                val remaining = stallLimit - (System.currentTimeMillis() - lastProgressAt)
                val event = if (remaining <= 0L) null else withTimeoutOrNull(remaining) { events.receive() }
                if (event == null) {
                    runCatching { service.cancelAndExit(request.requestId.value) }
        return VideoExecutionOutcome.Interrupted("视频 worker 长时间没有阶段推进，已请求退出进程")
                }
                when (event) {
                    is Event.Stage -> {
                        lastProgressAt = System.currentTimeMillis()
                        reportStage(event.stage, event.step, event.stepTotal)
                    }

                    is Event.Outcome -> {
                        return when (event.kind) {
                            "succeeded" -> VideoExecutionOutcome.Succeeded("output.mp4", event.detail)
                            "stopped" -> VideoExecutionOutcome.Stopped
                            else -> VideoExecutionOutcome.Failed(
                                event.code?.let { code -> runCatching { VideoErrorCode.valueOf(code) }.getOrNull() }
                                    ?: VideoErrorCode.NATIVE_EXECUTION_FAILED,
                                event.detail,
                            )
                        }
                    }

                    is Event.Interrupted -> return VideoExecutionOutcome.Interrupted(event.detail)
                }
            }
        } finally {
            active.remove(request.requestId.value)
            runCatching { context.unbindService(connection) }
        }
    }

    private suspend fun stageInputImage(
        binding: com.muyuchat.core.video.contract.VideoInputBinding,
        runDir: File,
    ): File? {
        val reference = binding.reference
        val target = File(runDir, "input.image")
        return withContext(Dispatchers.IO) {
            val source = when {
                reference.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(reference))
                reference.startsWith("/") -> File(reference).takeIf { it.isFile }?.inputStream()
                else -> null
            } ?: return@withContext null
            var written = 0L
            try {
                source.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > MAX_INPUT_BYTES) {
                                target.delete()
                                return@withContext null
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                runCatching { if (target.exists() && written == 0L) target.delete() }
            }
            val pinned = binding.sha256
            if (pinned != null) {
                val actual = sha256(target)
                if (!actual.equals(pinned, ignoreCase = true)) {
                    target.delete()
                    return@withContext null
                }
            }
            target
        }
    }

    private fun promptPair(request: VideoExecutionRequest): Pair<String, String> {
        val node = request.document.nodes.firstOrNull {
            VideoNodeTypeId.fromTypeId(it.typeId) == VideoNodeTypeId.INPUT_TEXT
        }
        val prompt = (node?.params?.get("prompt") as? VideoNodeValue.TextValue)?.text.orEmpty()
        val negative = (node?.params?.get("negative_prompt") as? VideoNodeValue.TextValue)?.text.orEmpty()
        return prompt to negative
    }

    private fun modelFilePaths(): List<File> {
        val manifest = Wan22Ti2v5bProfiles.Q4_0_Q4_K_M
        return manifest.components.map { File(modelRoot, it.relativePath) }
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
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private sealed class Event {
        data class Stage(val stage: VideoRunStage, val step: Long?, val stepTotal: Long?) : Event()
        data class Outcome(val kind: String, val code: String?, val detail: String?) : Event()
        data class Interrupted(val detail: String) : Event()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val PROGRESS_TIMEOUT_MS = 20L * 60L * 1000L
        // A spatially tiled full-VAE decode reports nothing between temporal tiles and can run
        // ~30 min at 480p, which is longer than the generic stall guard. Give that profile its own
        // bound instead of weakening the guard for every run.
        const val FULL_VAE_PROGRESS_TIMEOUT_MS = 60L * 60L * 1000L
        const val MAX_INPUT_BYTES = 32L * 1024L * 1024L
    }
}

/**
 * Prompt snapshot consistency check used by the worker before it calls native.
 *
 * The snapshot stores only `promptSha256` (privacy), so the worker must prove the text it is
 * about to send is the text the user approved. An empty pin means "not pinned" and is accepted.
 */
internal fun promptMatchesSnapshot(prompt: String, expectedSha256: String): Boolean {
    if (expectedSha256.isBlank()) return true
    val actual = java.security.MessageDigest.getInstance("SHA-256")
        .digest(prompt.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return actual.equals(expectedSha256, ignoreCase = true)
}

private val com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue.decimalToDouble: Double
    get() = unscaled.toDouble() / Math.pow(10.0, scale.toDouble())

/**
 * Builds the native request body for one attempt.
 *
 * Pure so the built-in-engine body stays byte-identical to the pre-engine protocol: the `engine`
 * key is only written when a non-default engine was frozen into the run's snapshot. Writing
 * `"engine": ""` instead would still select the built-in path, but it would change the request
 * bytes, and the default contract is "no engine chosen" means exactly today's request.
 */
internal fun videoNativeRequestPayload(
    runDir: String,
    stagingRoot: String,
    modelRoot: String,
    modelFingerprint: String,
    ditPath: String,
    encoderPath: String,
    vaePath: String,
    taePath: String = "",
    prompt: String,
    negativePrompt: String,
    imagePath: String,
    params: com.muyuchat.core.video.contract.VideoResolvedParams,
    threads: Int = 4,
    fullVaeDecode: Boolean = false,
): JSONObject = JSONObject().apply {
    put("runDir", runDir)
    put("stagingRoot", stagingRoot)
    put("modelRoot", modelRoot)
    put("modelFingerprint", modelFingerprint)
    put("ditPath", ditPath)
    put("encoderPath", encoderPath)
    put("vaePath", vaePath)
    if (taePath.isNotEmpty()) put("taePath", taePath)
    put("prompt", prompt)
    put("negativePrompt", negativePrompt)
    put("imagePath", imagePath)
    put("width", params.width)
    put("height", params.height)
    put("frames", params.frames)
    put("fps", params.fps)
    put("steps", params.steps)
    put("seed", params.seed)
    put("guidance", params.guidance.decimalToDouble)
    put("flowShift", params.flowShift.decimalToDouble)
    put("sampler", params.sampler)
    put("threads", threads)
    if (params.engine.isNotBlank()) {
        put("engine", params.engine)
        // The backend only exists for the external engine; the built-in request stays unchanged.
        put("backend", params.externalBackend)
        // TAE keeps the same latent in ~1/5 of the time; the full Wan VAE is only survivable at
        // 480p when the decoder is spatially tiled (tile_size is a LATENT size: 16 => 2x5 tiles on
        // a 60x104 latent). Both are device-local preferences frozen into the payload here.
        // The native seam only names the two profiles it knows: "default" is the full Wan VAE,
        // "tae" the lightweight head. Anything else is rejected as INPUT_INVALID.
        put("vaeProfile", if (fullVaeDecode) "default" else "tae")
        if (fullVaeDecode) {
            put(
                "tiling",
                JSONObject()
                    .put("spatial", true)
                    .put("temporal", true)
                    .put("tileSize", 16)
                    .put("tileFrames", 1),
            )
        }
        put("paramsBackend", "disk")
        // Distillation LoRA weights. Omitted when empty, so every built-in and plain-external
        // request keeps its previous bytes.
        if (params.loraSpecs.isNotEmpty()) {
            put(
                "loras",
                JSONArray().apply {
                    params.loraSpecs.forEach { spec ->
                        put(JSONObject().put("path", spec.path).put("weight", spec.weight))
                    }
                },
            )
        }
    }
}

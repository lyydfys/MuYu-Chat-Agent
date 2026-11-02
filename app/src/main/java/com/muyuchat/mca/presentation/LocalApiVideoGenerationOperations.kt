package com.muyuchat.mca.presentation

import com.muyuchat.api.local.VideoGenerationApiContract
import com.muyuchat.api.local.VideoGenerationApiRequest
import com.muyuchat.api.local.VideoGenerationProviderException
import com.muyuchat.core.video.contract.VideoCancelResult
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoRevisionResult
import com.muyuchat.core.video.contract.VideoRunId
import com.muyuchat.core.video.contract.VideoRunProjection
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.core.video.contract.VideoSubmitCommand
import com.muyuchat.core.video.contract.VideoSubmitResult
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowPort
import com.muyuchat.feature.video.workspace.VideoWorkflowTemplates
import com.muyuchat.mca.composition.VideoLocalModelPaths
import com.muyuchat.mca.video.data.VideoMediaRecord
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The authenticated `POST /v1/video` provider.
 *
 * It is a protocol adapter over the single process [VideoWorkflowPort]: the request is lowered to
 * the same canonical workflow document the page submits, frozen as a revision, admitted by the
 * owner (which remains the only state writer and the only executor selector) and awaited to its
 * terminal state. Nothing here starts a worker, a native call or a second state store.
 *
 * The engine is a frozen per-run execution choice: [VideoGenerationApiRequest.engine] travels
 * through [VideoSubmitCommand.engine] into the snapshot, and only a non-default value is ever
 * written into the native request body.
 */
internal interface LocalApiVideoExecutionPort {
    fun observeRuns(): Flow<List<VideoRunProjection>>

    fun observeRun(runId: VideoRunId): Flow<VideoRunProjection?>

    suspend fun saveRevision(document: VideoWorkflowDocument): VideoRevisionResult

    suspend fun submit(command: VideoSubmitCommand): VideoSubmitResult

    suspend fun requestCancel(runId: VideoRunId): VideoCancelResult
}

/** Adapts the process owner to the narrow surface the API provider needs. */
internal class OwnerBackedLocalApiVideoExecutionPort(
    private val port: VideoWorkflowPort,
) : LocalApiVideoExecutionPort {
    override fun observeRuns(): Flow<List<VideoRunProjection>> = port.observeRuns()

    override fun observeRun(runId: VideoRunId): Flow<VideoRunProjection?> = port.observeRun(runId)

    override suspend fun saveRevision(document: VideoWorkflowDocument): VideoRevisionResult =
        port.saveRevision(document)

    override suspend fun submit(command: VideoSubmitCommand): VideoSubmitResult = port.submit(command)

    override suspend fun requestCancel(runId: VideoRunId): VideoCancelResult =
        port.requestCancel(runId)
}

internal class LocalApiVideoOperations(
    private val port: LocalApiVideoExecutionPort,
    private val modelPaths: () -> VideoLocalModelPaths,
    private val mediaByRun: (String) -> List<VideoMediaRecord>,
    /**
     * Worker-reported execution evidence for a run: the external backend that actually ran and the
     * htp->cpu fallback marker. Read-only, in-process, empty when the worker reported nothing.
     */
    private val executionDetailByRun: (String) -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val runTimeoutMs: Long = DEFAULT_RUN_TIMEOUT_MS,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val admission = Mutex()
    private val activeRuns = ConcurrentHashMap<String, VideoRunId>()

    suspend fun generate(requestId: String, body: String): String {
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        val request = VideoGenerationApiContract.parseRequest(body)
        val expectedPaths = modelPaths()
        validateModelPaths(request, expectedPaths)
        requireModelFilesPresent(expectedPaths)

        val projection = admission.withLock {
            // One video executive: a second run must be refused, never queued behind a synchronous
            // HTTP call that could hang for the whole first run.
            port.observeRuns().first().firstOrNull { !it.isTerminal }?.let { busy ->
                throw VideoGenerationProviderException(
                    code = "video_generation_busy",
                    httpStatus = 409,
                    message = "Another video run is still in progress (${busy.runId.value}). " +
                        "Wait for it to finish or cancel it, then retry.",
                    detailsJson = JSONObject()
                        .put("run_id", busy.runId.value)
                        .put("state", busy.state.name)
                        .toString(),
                    retryAfterMs = 2_000L,
                )
            }
            if (activeRuns.containsKey(requestId)) {
                throw VideoGenerationProviderException(
                    code = "video_request_in_use",
                    httpStatus = 409,
                    message = "This request id is already running a video request.",
                )
            }
            val document = videoWorkflowDocumentForApi(request, ids())
            val frozen = when (val saved = port.saveRevision(document)) {
                is VideoRevisionResult.Saved -> saved.revisionId
                is VideoRevisionResult.Conflict -> throw VideoGenerationProviderException(
                    code = "video_revision_conflict",
                    httpStatus = 409,
                    message = "The frozen revision conflicts with an existing one; retry the request.",
                )
            }
            val admitted = when (val result = port.submit(
                VideoSubmitCommand(
                    revisionId = frozen,
                    backend = com.muyuchat.core.video.contract.VideoBackendKind.LOCAL_NATIVE,
                    // A caller-provided seed is authoritative; the request is fully reproducible.
                    seedOverride = request.seed,
                    engine = request.engine,
                    externalBackend = request.backend,
                    loraSpecs = request.loraSpecs,
                ),
            )) {
                is VideoSubmitResult.Admitted -> result.runId
                is VideoSubmitResult.Rejected -> throw submitRejection(result)
            }
            activeRuns[requestId] = admitted
            admitted
        }

        val terminal = try {
            awaitTerminal(projection, requestId)
        } finally {
            activeRuns.remove(requestId)
        }
        if (terminal.state != VideoRunState.COMPLETED) throw terminalFailure(terminal, request)
        val media = mediaByRun(terminal.runId.value).firstOrNull()
            ?: throw VideoGenerationProviderException(
                code = "video_media_missing",
                httpStatus = 502,
                message = "The run completed but no verified media row was published.",
                detailsJson = JSONObject().put("run_id", terminal.runId.value).toString(),
            )
        val detail = runCatching { executionDetailByRun(terminal.runId.value) }
            .getOrNull()
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
        val actualExternalBackend = detail?.optString("externalBackend")
            ?.takeIf { it.isNotBlank() }
            ?: request.backend
        val fallback = detail?.optString("fallback")?.takeIf { it.isNotBlank() }
        val fallbackReason = detail?.optString("reason")?.takeIf { it.isNotBlank() }
        return localApiVideoResultJson(
            requestId = requestId,
            request = request,
            projection = terminal,
            media = media,
            elapsedMs = clock() - terminal.createdAtMs,
            externalBackend = actualExternalBackend,
            fallback = fallback,
            fallbackReason = fallbackReason,
        )
    }

    /** Client-disconnect / scoped stop: cancels only the run bound to this request id. */
    suspend fun stop(requestId: String): Boolean {
        val runId = activeRuns[requestId] ?: return false
        return try {
            port.requestCancel(runId) is VideoCancelResult.Accepted
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun awaitTerminal(runId: VideoRunId, requestId: String): VideoRunProjection {
        val terminal = withTimeoutOrNull(runTimeoutMs) {
            port.observeRun(runId).first { it?.isTerminal == true }
        }
        if (terminal == null) {
            // A timed-out synchronous call must not leave the native run executing unattended.
            runCatching { port.requestCancel(runId) }
            throw VideoGenerationProviderException(
                code = "video_generation_timeout",
                httpStatus = 504,
                message = "The video run did not reach a terminal state within " +
                    "${runTimeoutMs / 60_000L} minutes; cancellation was requested.",
                detailsJson = JSONObject()
                    .put("run_id", runId.value)
                    .put("request_id", requestId)
                    .toString(),
            )
        }
        return terminal
    }

    private fun validateModelPaths(request: VideoGenerationApiRequest, expected: VideoLocalModelPaths) {
        fun confirm(name: String, provided: String?, allowed: String) {
            if (provided == null) return
            val providedPath = runCatching { File(provided).canonicalPath }.getOrNull()
            val allowedPath = runCatching { File(allowed).canonicalPath }.getOrNull()
            if (providedPath == null || allowedPath == null || providedPath != allowedPath) {
                throw VideoGenerationProviderException(
                    code = "video_model_path_mismatch",
                    httpStatus = 422,
                    message = "$name must name the manifest-resolved component this device loads; " +
                        "an arbitrary path is never injected into the worker.",
                    detailsJson = JSONObject().put("field", name).toString(),
                )
            }
        }
        confirm("ditPath", request.ditPath, expected.ditPath)
        confirm("vaePath", request.vaePath, expected.vaePath)
        confirm("tePath", request.tePath, expected.textEncoderPath)
    }

    private fun requireModelFilesPresent(expected: VideoLocalModelPaths) {
        val missing = listOf(
            "DiT" to expected.ditPath,
            "VAE" to expected.vaePath,
            "文本编码器" to expected.textEncoderPath,
        ).filterNot { (_, path) -> File(path).isFile }
        if (missing.isNotEmpty()) {
            throw VideoGenerationProviderException(
                code = "video_model_not_ready",
                httpStatus = 409,
                message = "The Wan2.2 TI2V-5B bundle is incomplete: missing " +
                    missing.joinToString("、") { it.first } +
                    ". Import or download the complete component set, then retry.",
            )
        }
    }

    private fun submitRejection(rejected: VideoSubmitResult.Rejected): VideoGenerationProviderException {
        val (status, code) = when (rejected.code) {
            VideoErrorCode.BACKEND_UNAVAILABLE -> 503 to "video_runtime_unavailable"
            VideoErrorCode.MODEL_MISSING,
            VideoErrorCode.MODEL_ROLE_MISMATCH,
            VideoErrorCode.MODEL_CHECKSUM_MISMATCH,
            VideoErrorCode.MODEL_LOAD_FAILED,
            -> 409 to "video_model_not_ready"
            VideoErrorCode.WORKFLOW_INVALID,
            VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
            VideoErrorCode.WORKFLOW_CYCLE,
            VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
            VideoErrorCode.INPUT_INVALID,
            VideoErrorCode.INPUT_MISSING,
            VideoErrorCode.INPUT_TOO_LARGE,
            -> 422 to "video_request_invalid"
            else -> 500 to "video_generation_failed"
        }
        return VideoGenerationProviderException(
            code = code,
            httpStatus = status,
            message = rejected.detail,
            detailsJson = JSONObject().put("video_error_code", rejected.code.name).toString(),
        )
    }

    private fun terminalFailure(
        projection: VideoRunProjection,
        request: VideoGenerationApiRequest,
    ): VideoGenerationProviderException {
        val cancelled = projection.state == VideoRunState.CANCELLED
        val details = JSONObject()
            .put("run_id", projection.runId.value)
            .put("attempt_id", projection.attemptId.value)
            .put("state", projection.state.name)
            .put("stage", projection.stage.name)
            .put("engine", request.engine)
        projection.errorCode?.let { details.put("video_error_code", it.name) }
        return VideoGenerationProviderException(
            code = if (cancelled) "video_generation_cancelled" else "video_generation_failed",
            httpStatus = if (cancelled) 409 else 500,
            message = projection.errorDetail
                ?: if (cancelled) {
                    "The video run was cancelled."
                } else {
                    "The video run failed at ${projection.stage.name}."
                },
            detailsJson = details.toString(),
        )
    }

    internal companion object {
        // 30 min was shorter than the workload itself: the same 480p x 5 full-VAE recipe takes
        // 1926 s in the shell probe, and a device run was cancelled at 30 min with the tiling loop
        // still active (2026-11-02 10:15). 60 min matches the worker's full-VAE stall bound.
        const val DEFAULT_RUN_TIMEOUT_MS: Long = 60L * 60L * 1_000L
    }
}

/**
 * Lowers one `/v1/video` request onto the canonical MCA-native workflow.
 *
 * The graph is the same text-to-video template the page edits; only the requested fps and steps
 * are applied to the nodes that own them. Every value keeps going through the shared validator and
 * compiler, so the API can never bypass the frozen node schema.
 */
internal fun videoWorkflowDocumentForApi(
    request: VideoGenerationApiRequest,
    idSuffix: String,
): VideoWorkflowDocument {
    val document = VideoWorkflowTemplates.textToVideoTemplate(
        workflowId = "wf-api-$idSuffix",
        revisionId = "rev-api-$idSuffix",
        prompt = request.prompt,
        width = request.width,
        height = request.height,
        frames = request.frames,
        seed = request.seed,
    )
    // Distilled (Turbo/Lightning) LoRAs run without classifier-free guidance; CFG 6.0 on a 4-step
    // LoRA produced a featureless blob, while guidance 1.0 produced a coherent image on device
    // (2026-11-02 11:28 A/B, same seed/prompt). An explicit request value still wins.
    val guidanceOverride: Double? = request.guidance
        ?: if (request.loraSpecs.isNotEmpty()) 1.0 else null
    return document.copy(
        nodes = document.nodes.map { node ->
            when (VideoNodeTypeId.fromTypeId(node.typeId)) {
                VideoNodeTypeId.ENCODE_MP4 -> node.copy(
                    params = node.params + ("fps" to VideoNodeValue.LongValue(request.fps)),
                )
                VideoNodeTypeId.WAN_SAMPLER -> {
                    var params = node.params + ("steps" to VideoNodeValue.LongValue(request.steps))
                    guidanceOverride?.let { guidance ->
                        params = params + (
                            "guidance" to VideoNodeValue.DecimalValue(
                                Math.round(guidance * 10.0),
                                1,
                            )
                            )
                    }
                    node.copy(params = params)
                }
                else -> node
            }
        },
    )
}

/**
 * Response body of a successful run.
 *
 * Field names follow `POST /v1/images/generations` (`created` / `request_id` / `model` /
 * `execution` / `data[]`); the `data` item publishes the verified media evidence rather than
 * embedding an MP4, so a synchronous response stays small.
 */
internal fun localApiVideoResultJson(
    requestId: String,
    request: VideoGenerationApiRequest,
    projection: VideoRunProjection,
    media: VideoMediaRecord,
    elapsedMs: Long,
    modelId: String = "wan2.2-ti2v-5b",
    /** Backend that actually produced the frames (cpu after a successful htp fallback). */
    externalBackend: String = request.backend,
    /** Non-null only when the native layer fell back; the value is the `htp->cpu` marker. */
    fallback: String? = null,
    /** Stable, parseable fallback reason (for example `htp_unavailable: fastrpc_denied`). */
    fallbackReason: String? = null,
): String = JSONObject()
    .put("created", projection.createdAtMs / 1_000L)
    .put("request_id", requestId)
    .put("model", modelId)
    .put("engine", request.engine)
    .put("external_backend", externalBackend)
    .apply { if (fallback != null) put("external_backend_fallback", fallback) }
    .apply { if (fallbackReason != null) put("external_backend_reason", fallbackReason) }
    .put(
        "execution",
        JSONObject()
            .put("run_id", projection.runId.value)
            .put("attempt_id", projection.attemptId.value)
            .put("state", projection.state.name)
            .put("stage", projection.stage.name)
            .put("backend", com.muyuchat.core.video.contract.VideoBackendKind.LOCAL_NATIVE.name)
            .put("engine", request.engine)
            .put("external_backend", externalBackend)
            .apply { if (fallback != null) put("external_backend_fallback", fallback) }
            .apply { if (fallbackReason != null) put("external_backend_reason", fallbackReason) }
            .put("width", request.width)
            .put("height", request.height)
            .put("frames", request.frames)
            .put("fps", request.fps)
            .put("steps", request.steps)
            .put("seed", request.seed)
            .put("elapsed_ms", elapsedMs.coerceAtLeast(0L)),
    )
    .put(
        "data",
        JSONArray().put(
            JSONObject()
                .put("index", 0)
                .put("media_id", media.mediaId)
                .put("mime_type", media.mimeType)
                .put("size_bytes", media.sizeBytes)
                .put("sha256", media.sha256)
                .put("width", media.width)
                .put("height", media.height)
                .put("frame_count", media.frameCount)
                .put("fps", media.fps)
                .put("duration_us", media.durationUs)
                .put("codec", media.codec),
        ),
    )
    .toString()

package com.muyuchat.mca.video.data

import com.muyuchat.core.video.contract.VideoBackendKind
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRunSnapshot
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.core.video.contract.VideoRunTransitions
import com.muyuchat.core.video.contract.VideoResourceState
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowSource
import com.muyuchat.mca.video.model.VideoModelManifest
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence boundary of [com.muyuchat.mca.video.execution.VideoGenerationOwner]. The owner
 * depends on this INTERFACE so its whole state machine is JVM-testable with an in-memory fake;
 * [RoomVideoRunStore] is the production implementation over the shared mca.db. The store never
 * decides state — it only executes conditional transactions and reports affected rows.
 */
interface VideoRunStore {

    fun revisionById(revisionId: String): VideoRevisionRecord?

    fun decodeRevisionDocument(revision: VideoRevisionRecord): VideoWorkflowDocument?

    fun manifestFor(backend: VideoBackendKind): VideoModelManifest?

    fun nextSeed(): Long

    fun runById(runId: String): VideoRunRow?

    fun nonTerminalRuns(): List<VideoRunRow>

    fun recentRuns(limit: Int): List<VideoRunRow>

    /** Returns false when the runId already exists (duplicate submit protection). */
    fun insertRun(
        runId: String,
        attemptId: String,
        requestId: String,
        snapshot: VideoRunSnapshot,
        backend: VideoBackendKind,
        state: VideoRunState,
        nowMs: Long,
        remoteEndpointId: String? = null,
    ): Boolean

    /** Conditional metadata write for a remote prompt id; 0 rows = stale/attempt mismatch. */
    fun recordRemotePromptId(runId: String, attemptId: String, promptId: String, nowMs: Long): Boolean

    /** Conditional transition; 0 rows = stale writer. */
    fun transition(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        fromState: String,
        toState: VideoRunState,
        stage: String,
        resourceState: VideoResourceState,
        errorCode: VideoErrorCode?,
        errorDetail: String?,
    ): Int

    fun updateProgress(runId: String, attemptId: String, stage: VideoRunStage, step: Long?, stepTotal: Long?, nowMs: Long)

    fun appendEvent(runId: String, attemptId: String, state: VideoRunState, stage: VideoRunStage, message: String?, nowMs: Long)

    fun decodeSnapshot(snapshotJson: String, seedOverride: Long?): VideoRunSnapshot?

    /** Persists an immutable revision of the draft; returns the stored id (digest-verified). */
    fun saveRevision(document: VideoWorkflowDocument, nowMs: Long): String?

    /** Published media, newest first, excluding tombstoned rows. */
    fun recentMedia(limit: Int): List<VideoMediaRecord>

    fun allMedia(): List<VideoMediaRecord>

    fun mediaById(mediaId: String): VideoMediaRecord?

    /** Published media rows of one run, newest first (empty for unknown/never-published runs). */
    fun mediaByRun(runId: String): List<VideoMediaRecord>

    /**
     * Atomic terminal commit: conditional run transition (from [fromState]) + output reference +
     * media row. Returns false when the run was already settled by another writer; nothing is
     * inserted in that case.
     */
    fun completeRunWithMedia(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        fromState: String,
        media: VideoMediaRecord,
        nowMs: Long,
    ): Boolean

    /** Tombstone first. Returns true when the row is now tombstoned (already-tombstoned is true). */
    fun markMediaDeleted(mediaId: String, nowMs: Long): Boolean

    /** Removes the tombstoned row; only called after the owned file is gone or confirmed absent. */
    fun deleteTombstonedMedia(mediaId: String): Int
}

data class VideoMediaRecord(
    val mediaId: String,
    val runId: String,
    val mimeType: String,
    /** Relative to the owned video media root; never an absolute or user-supplied path. */
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Long,
    val height: Long,
    val durationUs: Long,
    val fps: Long,
    val frameCount: Long,
    val codec: String,
    val hasAudio: Boolean,
    val createdAt: Long,
    val deletedAt: Long?,
)

data class VideoRevisionRecord(
    val revisionId: String,
    val workflowId: String,
    val source: String,
    val schemaVersion: Int,
    val canonicalGraphJson: String,
    val canonicalSha256: String,
)

data class VideoRunRow(
    val runId: String,
    val attemptId: String,
    val requestId: String,
    val stateVersion: Int,
    val snapshotJson: String,
    val workflowId: String,
    val revisionId: String,
    val backend: String,
    val state: String,
    val resourceState: String,
    val stage: String,
    val step: Long?,
    val stepTotal: Long?,
    val errorCode: String?,
    val errorDetail: String?,
    val outputMediaId: String?,
    val remoteEndpointId: String?,
    val remotePromptId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val terminalAt: Long?,
) {
    fun toProjection(): com.muyuchat.core.video.contract.VideoRunProjection =
        com.muyuchat.core.video.contract.VideoRunProjection(
            runId = com.muyuchat.core.video.contract.VideoRunId(runId),
            attemptId = com.muyuchat.core.video.contract.VideoAttemptId(attemptId),
            workflowId = com.muyuchat.core.video.contract.VideoWorkflowId(workflowId),
            revisionId = com.muyuchat.core.video.contract.VideoRevisionId(revisionId),
            state = VideoRunState.valueOf(state),
            stage = runStageOf(stage),
            step = step,
            stepTotal = stepTotal,
            resourceState = VideoResourceState.valueOf(resourceState),
            errorCode = errorCode?.let { runCatching { VideoErrorCode.valueOf(it) }.getOrNull() },
            errorDetail = errorDetail,
            outputMediaId = outputMediaId?.let { com.muyuchat.core.video.contract.VideoMediaAssetId(it) },
            remoteEndpointId = remoteEndpointId,
            remotePromptId = remotePromptId,
            createdAtMs = createdAt,
            updatedAtMs = updatedAt,
            terminalAtMs = terminalAt,
        )

    private fun runStageOf(name: String): VideoRunStage =
        runCatching { VideoRunStage.valueOf(name) }.getOrDefault(VideoRunStage.NONE)
}

/**
 * Production store over the shared mca.db. Room DAO calls run on Dispatchers.IO; every write is
 * the conditional SQL from [VideoWorkflowDao]. Snapshot/revision JSON codecs live here and only
 * here: the owner and tests exchange typed values.
 */
class RoomVideoRunStore(
    private val dao: VideoWorkflowDao,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val seedSource: java.security.SecureRandom = java.security.SecureRandom(),
) : VideoRunStore {

    override fun revisionById(revisionId: String): VideoRevisionRecord? =
        runIO { dao.revisionById(revisionId) }?.toRecord()

    override fun decodeRevisionDocument(revision: VideoRevisionRecord): VideoWorkflowDocument? =
        runCatching { VideoGraphJsonCodec.decodeDocument(revision.canonicalGraphJson) }.getOrNull()

    override fun manifestFor(backend: VideoBackendKind): VideoModelManifest? = when (backend) {
        // Remote runs still bind the pinned local manifest for snapshot geometry/identity; the
        // remote server's own model choice is an explicit, separate binding in the raw graph.
        VideoBackendKind.LOCAL_NATIVE, VideoBackendKind.REMOTE_COMFY -> Wan22Ti2v5bProfiles.Q4_0_Q4_K_M
        else -> null
    }

    override fun nextSeed(): Long {
        var seed = seedSource.nextLong()
        if (seed == 0L) seed = 1L
        return seed
    }

    override fun runById(runId: String): VideoRunRow? = runIO { dao.runById(runId) }?.toRow()

    override fun nonTerminalRuns(): List<VideoRunRow> = runIO {
        dao.runsInStates(
            listOf(
                VideoRunState.QUEUED.name,
                VideoRunState.PREPARING.name,
                VideoRunState.CONDITIONING.name,
                VideoRunState.SAMPLING.name,
                VideoRunState.DECODING.name,
                VideoRunState.ENCODING.name,
                VideoRunState.PUBLISHING.name,
                VideoRunState.CANCEL_REQUESTED.name,
                VideoRunState.UNKNOWN.name,
                VideoRunState.RECONCILING.name,
            ),
        )
    }.map { it.toRow() }

    override fun recentRuns(limit: Int): List<VideoRunRow> = runIO { dao.recentRuns(limit) }.map { it.toRow() }

    override fun insertRun(
        runId: String,
        attemptId: String,
        requestId: String,
        snapshot: VideoRunSnapshot,
        backend: VideoBackendKind,
        state: VideoRunState,
        nowMs: Long,
        remoteEndpointId: String?,
    ): Boolean = runIO {        if (dao.runById(runId) != null) {
            false
        } else {
            dao.insertRun(
                VideoRunEntity(
                    runId = runId,
                    attemptId = attemptId,
                    requestId = requestId,
                    stateVersion = 0,
                    workflowId = snapshot.workflowId.value,
                    revisionId = snapshot.revisionId.value,
                    backend = backend.name,
                    modelFingerprint = snapshot.modelFingerprint,
                    profileFingerprint = snapshot.profileFingerprint,
                    snapshotJson = VideoSnapshotCodec.encode(snapshot),
                    state = state.name,
                    resourceState = VideoResourceState.HELD.name,
                    stage = VideoRunStage.NONE.name,
                    step = null,
                    stepTotal = null,
                    errorCode = null,
                    errorDetail = null,
                    outputMediaId = null,
                    remoteEndpointId = remoteEndpointId,
                    remotePromptId = null,
                    createdAt = nowMs,
                    updatedAt = nowMs,
                    terminalAt = null,
                ),
            )
            true
        }
    }

    override fun recordRemotePromptId(runId: String, attemptId: String, promptId: String, nowMs: Long): Boolean =
        runIO { dao.attachRemotePromptId(runId, attemptId, promptId, nowMs) > 0 }

    override fun transition(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        fromState: String,
        toState: VideoRunState,
        stage: String,
        resourceState: VideoResourceState,
        errorCode: VideoErrorCode?,
        errorDetail: String?,
    ): Int = runIO {
        val terminalAt = if (VideoRunTransitions.isTerminal(toState)) clockMs() else null
        val rows = dao.transitionRun(
            runId = runId,
            attemptId = attemptId,
            expectedVersion = expectedVersion,
            fromState = fromState,
            toState = toState.name,
            stage = stage,
            step = null,
            stepTotal = null,
            errorCode = errorCode?.name,
            errorDetail = errorDetail,
            terminalAt = terminalAt,
            nowMs = clockMs(),
        )
        if (rows > 0) {
            dao.updateRunResourceState(runId, attemptId, resourceState.name, clockMs())
        }
        rows
    }

    override fun updateProgress(runId: String, attemptId: String, stage: VideoRunStage, step: Long?, stepTotal: Long?, nowMs: Long) {
        runIO { dao.updateRunProgress(runId, attemptId, stage.name, step, stepTotal, nowMs) }
    }

    override fun appendEvent(runId: String, attemptId: String, state: VideoRunState, stage: VideoRunStage, message: String?, nowMs: Long) {
        runIO {
            dao.insertRunEvent(VideoRunEventEntity(runId = runId, attemptId = attemptId, state = state.name, stage = stage.name, message = message, atMs = nowMs))
            dao.trimRunEvents(runId, keep = 200)
        }
    }

    override fun decodeSnapshot(snapshotJson: String, seedOverride: Long?): VideoRunSnapshot? =
        runCatching { VideoSnapshotCodec.decode(snapshotJson, seedOverride) }.getOrNull()

    override fun saveRevision(document: VideoWorkflowDocument, nowMs: Long): String? = runIO {
        val canonical = VideoGraphJsonCodec.encodeDocument(document)
        val digest = com.muyuchat.core.video.contract.VideoWorkflowCanon.canonicalExecutableSha256(document)
        val existing = dao.revisionById(document.revisionId.value)
        val row = VideoWorkflowRevisionEntity(
            revisionId = document.revisionId.value,
            workflowId = document.workflowId.value,
            source = document.source.name,
            schemaVersion = document.schemaVersion,
            canonicalGraphJson = canonical,
            rawSourceRef = document.rawSourceRef,
            canonicalSha256 = digest,
            createdAt = existing?.createdAt ?: nowMs,
            updatedAt = nowMs,
        )
        if (existing != null && existing.canonicalSha256 != digest) {
            // Revisions are immutable: a changed graph must mint a new revision id upstream.
            null
        } else if (existing == null) {
            dao.insertRevision(row)
            document.revisionId.value
        } else {
            document.revisionId.value
        }
    }

    override fun recentMedia(limit: Int): List<VideoMediaRecord> =
        runIO { dao.recentMedia(limit) }.map { it.toRecord() }

    override fun allMedia(): List<VideoMediaRecord> = runIO { dao.allMedia() }.map { it.toRecord() }

    override fun mediaById(mediaId: String): VideoMediaRecord? =
        runIO { dao.mediaById(mediaId) }?.toRecord()

    override fun mediaByRun(runId: String): List<VideoMediaRecord> =
        runIO { dao.mediaByRun(runId) }.map { it.toRecord() }

    override fun completeRunWithMedia(
        runId: String,
        attemptId: String,
        expectedVersion: Int,
        fromState: String,
        media: VideoMediaRecord,
        nowMs: Long,
    ): Boolean = runIO {
        dao.completeRunWithMedia(
            runId = runId,
            attemptId = attemptId,
            expectedVersion = expectedVersion,
            fromState = fromState,
            stage = VideoRunStage.FINALIZE.name,
            media = VideoMediaAssetEntity(
                mediaId = media.mediaId,
                runId = media.runId,
                mimeType = media.mimeType,
                relativePath = media.relativePath,
                sizeBytes = media.sizeBytes,
                sha256 = media.sha256,
                width = media.width,
                height = media.height,
                durationUs = media.durationUs,
                fps = media.fps,
                frameCount = media.frameCount,
                codec = media.codec,
                hasAudio = media.hasAudio,
                createdAt = media.createdAt,
                deletedAt = null,
            ),
            nowMs = nowMs,
        )
    }

    override fun markMediaDeleted(mediaId: String, nowMs: Long): Boolean = runIO {
        val row = dao.mediaById(mediaId) ?: return@runIO false
        if (row.deletedAt != null) return@runIO true
        dao.markMediaDeleted(mediaId, nowMs) == 1
    }

    override fun deleteTombstonedMedia(mediaId: String): Int =
        runIO { dao.deleteTombstonedMedia(mediaId) }

    private fun VideoWorkflowRevisionEntity.toRecord() = VideoRevisionRecord(
        revisionId = revisionId,
        workflowId = workflowId,
        source = source,
        schemaVersion = schemaVersion,
        canonicalGraphJson = canonicalGraphJson,
        canonicalSha256 = canonicalSha256,
    )

    private fun VideoMediaAssetEntity.toRecord() = VideoMediaRecord(
        mediaId = mediaId,
        runId = runId,
        mimeType = mimeType,
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        width = width,
        height = height,
        durationUs = durationUs,
        fps = fps,
        frameCount = frameCount,
        codec = codec,
        hasAudio = hasAudio,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )

    private fun VideoRunEntity.toRow() = VideoRunRow(
        runId = runId,
        attemptId = attemptId,
        requestId = requestId,
        stateVersion = stateVersion,
        snapshotJson = snapshotJson,
        workflowId = workflowId,
        revisionId = revisionId,
        backend = backend,
        state = state,
        resourceState = resourceState,
        stage = stage,
        step = step,
        stepTotal = stepTotal,
        errorCode = errorCode,
        errorDetail = errorDetail,
        outputMediaId = outputMediaId,
        remoteEndpointId = remoteEndpointId,
        remotePromptId = remotePromptId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        terminalAt = terminalAt,
    )

    private fun <T> runIO(block: () -> T): T = kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
}

/** org.json codecs for the persisted video rows (document graph + run snapshot). */
object VideoGraphJsonCodec {

    fun encodeDocument(document: com.muyuchat.core.video.contract.VideoWorkflowDocument): String {
        val root = JSONObject()
        root.put("workflowId", document.workflowId.value)
        root.put("revisionId", document.revisionId.value)
        root.put("schemaVersion", document.schemaVersion)
        root.put("source", document.source.name)
        root.put("metadata", JSONObject(document.metadata))
        document.rawSourceRef?.let { root.put("rawSourceRef", it) }
        val nodes = JSONArray()
        document.nodes.forEach { node ->
            val n = JSONObject()
            n.put("nodeId", node.nodeId)
            n.put("typeId", node.typeId)
            n.put("typeVersion", node.typeVersion)
            val params = JSONObject()
            node.params.forEach { (name, value) ->
                params.put(name, VideoSnapshotCodec.encodeValue(value))
            }
            n.put("params", params)
            val ui = JSONObject()
            node.ui.positionX?.let { ui.put("x", it.toDouble()) }
            node.ui.positionY?.let { ui.put("y", it.toDouble()) }
            if (node.ui.collapsed) ui.put("collapsed", true)
            node.ui.label?.let { ui.put("label", it) }
            n.put("ui", ui)
            nodes.put(n)
        }
        root.put("nodes", nodes)
        val edges = JSONArray()
        document.edges.forEach { edge ->
            val e = JSONObject()
            e.put("from", edge.fromNodeId)
            e.put("fromPort", edge.fromPort)
            e.put("to", edge.toNodeId)
            e.put("toPort", edge.toPort)
            edges.put(e)
        }
        root.put("edges", edges)
        return root.toString()
    }

    fun decodeDocument(json: String): com.muyuchat.core.video.contract.VideoWorkflowDocument {
        val root = JSONObject(json)
        val nodes = mutableListOf<com.muyuchat.core.video.contract.VideoNode>()
        val nodeArray = root.getJSONArray("nodes")
        for (i in 0 until nodeArray.length()) {
            val n = nodeArray.getJSONObject(i)
            val params = LinkedHashMap<String, com.muyuchat.core.video.contract.VideoNodeValue>()
            val paramsObj = n.getJSONObject("params")
            for (name in paramsObj.keys()) {
                VideoSnapshotCodec.decodeValue(paramsObj.getJSONObject(name))?.let { params[name] = it }
            }
            val uiObj = n.optJSONObject("ui")
            nodes += com.muyuchat.core.video.contract.VideoNode(
                nodeId = n.getString("nodeId"),
                typeId = n.getString("typeId"),
                typeVersion = n.getInt("typeVersion"),
                params = params,
                ui = com.muyuchat.core.video.contract.VideoNodeUi(
                    positionX = uiObj?.optDouble("x")?.toFloat(),
                    positionY = uiObj?.optDouble("y")?.toFloat(),
                    collapsed = uiObj?.optBoolean("collapsed") ?: false,
                    label = uiObj?.optString("label")?.takeIf { it.isNotEmpty() && it != "null" },
                ),
            )
        }
        val edges = mutableListOf<com.muyuchat.core.video.contract.VideoEdge>()
        val edgeArray = root.getJSONArray("edges")
        for (i in 0 until edgeArray.length()) {
            val e = edgeArray.getJSONObject(i)
            edges += com.muyuchat.core.video.contract.VideoEdge(
                e.getString("from"), e.getString("fromPort"), e.getString("to"), e.getString("toPort"),
            )
        }
        val metadata = LinkedHashMap<String, String>()
        root.optJSONObject("metadata")?.let { obj -> for (k in obj.keys()) metadata[k] = obj.getString(k) }
        return com.muyuchat.core.video.contract.VideoWorkflowDocument(
            workflowId = com.muyuchat.core.video.contract.VideoWorkflowId(root.getString("workflowId")),
            revisionId = com.muyuchat.core.video.contract.VideoRevisionId(root.getString("revisionId")),
            schemaVersion = root.getInt("schemaVersion"),
            source = runCatching { VideoWorkflowSource.valueOf(root.getString("source")) }.getOrDefault(VideoWorkflowSource.MCA_NATIVE),
            nodes = nodes,
            edges = edges,
            metadata = metadata,
            rawSourceRef = root.optString("rawSourceRef").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

/** Snapshot JSON codec; decimals keep their exact (unscaled, scale) pair — never floats. */
object VideoSnapshotCodec {

    fun encode(snapshot: VideoRunSnapshot): String {
        val root = JSONObject()
        root.put("workflowId", snapshot.workflowId.value)
        root.put("revisionId", snapshot.revisionId.value)
        root.put("canonicalSha256", snapshot.canonicalSha256)
        root.put("modelFingerprint", snapshot.modelFingerprint)
        root.put("profileFingerprint", snapshot.profileFingerprint)
        root.put("backend", snapshot.backend.name)
        root.put("params", encodeParams(snapshot.resolvedParams))
        val refs = JSONArray()
        snapshot.inputRefs.forEach { (key, binding) ->
            refs.put(
                JSONObject()
                    .put("key", key)
                    .put("nodeId", binding.nodeId)
                    .put("kind", binding.kind.name)
                    .put("reference", binding.reference)
                    .put("sha256", binding.sha256 ?: ""),
            )
        }
        root.put("inputRefs", refs)
        snapshot.nativeEffectiveParams?.let { root.put("nativeEffectiveParams", encodeParams(it)) }
        return root.toString()
    }

    fun decode(json: String, seedOverride: Long? = null): VideoRunSnapshot {
        val root = JSONObject(json)
        val refs = LinkedHashMap<String, com.muyuchat.core.video.contract.VideoInputBinding>()
        root.optJSONArray("inputRefs")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                refs[item.getString("key")] = com.muyuchat.core.video.contract.VideoInputBinding(
                    nodeId = item.getString("nodeId"),
                    kind = runCatching { com.muyuchat.core.video.contract.VideoPortKind.valueOf(item.getString("kind")) }
                        .getOrDefault(com.muyuchat.core.video.contract.VideoPortKind.TEXT),
                    reference = item.getString("reference"),
                    sha256 = item.optString("sha256").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
        }
        return VideoRunSnapshot(
            workflowId = com.muyuchat.core.video.contract.VideoWorkflowId(root.getString("workflowId")),
            revisionId = com.muyuchat.core.video.contract.VideoRevisionId(root.getString("revisionId")),
            canonicalSha256 = root.getString("canonicalSha256"),
            inputRefs = refs,
            requestedParams = decodeParams(root.getJSONObject("params")),
            resolvedParams = decodeParams(root.getJSONObject("params"), seedOverride),
            nativeEffectiveParams = root.optJSONObject("nativeEffectiveParams")?.let { decodeParams(it) },
            modelFingerprint = root.getString("modelFingerprint"),
            profileFingerprint = root.getString("profileFingerprint"),
            backend = runCatching { VideoBackendKind.valueOf(root.getString("backend")) }.getOrDefault(VideoBackendKind.LOCAL_NATIVE),
        )
    }

    fun encodeValue(value: com.muyuchat.core.video.contract.VideoNodeValue): JSONObject {
        val obj = JSONObject()
        when (value) {
            is com.muyuchat.core.video.contract.VideoNodeValue.TextValue -> obj.put("kind", "t").put("text", value.text)
            is com.muyuchat.core.video.contract.VideoNodeValue.LongValue -> obj.put("kind", "l").put("value", value.value)
            is com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue ->
                obj.put("kind", "d").put("unscaled", value.unscaled).put("scale", value.scale)
            is com.muyuchat.core.video.contract.VideoNodeValue.ImageAssetRefValue -> obj.put("kind", "i").put("assetId", value.assetId)
            is com.muyuchat.core.video.contract.VideoNodeValue.ModelRefValue -> obj.put("kind", "m").put("modelId", value.modelId)
        }
        return obj
    }

    fun decodeValue(obj: JSONObject): com.muyuchat.core.video.contract.VideoNodeValue? = when (obj.getString("kind")) {
        "t" -> com.muyuchat.core.video.contract.VideoNodeValue.TextValue(obj.getString("text"))
        "l" -> com.muyuchat.core.video.contract.VideoNodeValue.LongValue(obj.getLong("value"))
        "d" -> com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue(obj.getLong("unscaled"), obj.getInt("scale"))
        "i" -> com.muyuchat.core.video.contract.VideoNodeValue.ImageAssetRefValue(obj.getString("assetId"))
        "m" -> com.muyuchat.core.video.contract.VideoNodeValue.ModelRefValue(obj.getString("modelId"))
        else -> null
    }

    private fun encodeParams(params: com.muyuchat.core.video.contract.VideoResolvedParams): JSONObject {
        val obj = JSONObject()
        obj.put("width", params.width)
        obj.put("height", params.height)
        obj.put("frames", params.frames)
        obj.put("fps", params.fps)
        obj.put("steps", params.steps)
        obj.put("sampler", params.sampler)
        obj.put("guidance", encodeValue(params.guidance))
        obj.put("flowShift", encodeValue(params.flowShift))
        obj.put("seed", params.seed)
        obj.put("promptSha256", params.promptSha256)
        params.negativePromptSha256?.let { obj.put("negativePromptSha256", it) }
        // Empty (built-in) is written explicitly so a round-trip keeps the frozen choice; the
        // native request is what omits the field when empty, not this record.
        obj.put("engine", params.engine)
        // The external backend is frozen next to the engine so retries keep the same transport.
        obj.put("externalBackend", params.externalBackend)
        // Omitted when empty so rows written before LoRA support keep their exact bytes.
        if (params.loraSpecs.isNotEmpty()) {
            obj.put(
                "loras",
                JSONArray().apply {
                    params.loraSpecs.forEach { spec ->
                        put(JSONObject().put("path", spec.path).put("weight", spec.weight))
                    }
                },
            )
        }
        params.imageRef?.let {
            obj.put(
                "imageRef",
                JSONObject().put("nodeId", it.nodeId).put("kind", it.kind.name).put("reference", it.reference).put("sha256", it.sha256 ?: ""),
            )
        }
        return obj
    }

    private fun decodeParams(obj: JSONObject, seedOverride: Long? = null): com.muyuchat.core.video.contract.VideoResolvedParams {
        val imageRefObj = obj.optJSONObject("imageRef")
        return com.muyuchat.core.video.contract.VideoResolvedParams(
            width = obj.getLong("width"),
            height = obj.getLong("height"),
            frames = obj.getLong("frames"),
            fps = obj.getLong("fps"),
            steps = obj.getLong("steps"),
            sampler = obj.getString("sampler"),
            guidance = decodeValue(obj.getJSONObject("guidance")) as com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue,
            flowShift = decodeValue(obj.getJSONObject("flowShift")) as com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue,
            seed = seedOverride ?: obj.getLong("seed"),
            promptSha256 = obj.getString("promptSha256"),
            negativePromptSha256 = obj.optString("negativePromptSha256").takeIf { it.isNotEmpty() && it != "null" },
            imageRef = imageRefObj?.let {
                com.muyuchat.core.video.contract.VideoInputBinding(
                    nodeId = it.getString("nodeId"),
                    kind = runCatching { com.muyuchat.core.video.contract.VideoPortKind.valueOf(it.getString("kind")) }
                        .getOrDefault(com.muyuchat.core.video.contract.VideoPortKind.IMAGE_REF),
                    reference = it.getString("reference"),
                    sha256 = it.optString("sha256").takeIf { s -> s.isNotEmpty() && s != "null" },
                )
            },
            // Rows written before the engine field existed decode to the built-in engine, which is
            // exactly the behaviour they ran with.
            engine = obj.optString("engine")
                .takeIf { com.muyuchat.core.video.contract.VideoLocalEngine.isSupported(it) }
                ?: com.muyuchat.core.video.contract.VideoLocalEngine.DEFAULT,
            externalBackend = obj.optString("externalBackend")
                .takeIf { com.muyuchat.core.video.contract.VideoLocalBackend.isSupported(it) }
                ?: com.muyuchat.core.video.contract.VideoLocalBackend.HTP,
            loraSpecs = obj.optJSONArray("loras")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        runCatching {
                            add(
                                com.muyuchat.core.video.contract.VideoLoraBinding(
                                    path = item.optString("path"),
                                    weight = item.optDouble("weight", 1.0),
                                ),
                            )
                        }
                    }
                }
            } ?: emptyList(),
        )
    }
}

package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.core.video.contract.VideoModelAssetPort
import com.muyuchat.core.video.contract.VideoWorkflowPort
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.video.data.RoomVideoRunStore
import com.muyuchat.mca.video.data.VideoMediaRecord
import com.muyuchat.mca.video.execution.VideoGenerationOwner
import com.muyuchat.mca.video.media.VideoMediaPublisher
import com.muyuchat.mca.video.model.VideoModelAssetOwner
import com.muyuchat.mca.video.model.VideoModelComponentRole
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.mca.video.remote.RemoteComfyExecutionAdapter
import com.muyuchat.mca.video.worker.LocalVideoWorkerAdapter
import com.muyuchat.mca.video.workflow.VideoImportAdapter
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

/**
 * Process-level assembly of the native video workflow (proposal §3.2, V1-02a/V1-03/V1-04).
 *
 * It owns exactly one [VideoGenerationOwner] per process and exactly one worker adapter and media
 * publisher. Re-entering the video tab can never build a second owner or runtime; leaving pages
 * never tears it down — its lifetime is the runtime root's closing barrier, not any Activity/
 * Compose scope. The owner is the only business state writer; the adapter forwards to the
 * disposable :local_video process and the publisher only performs file protocol + reports back.
 */
internal class VideoHostAssembly(
    application: Application,
    scope: CoroutineScope,
) {
    private val created = AtomicBoolean(false)

    /** Kept for lazily derived model paths; the assembly itself never exposes the Application. */
    private val appContext: Application = application

    private val store: RoomVideoRunStore = RoomVideoRunStore(
        dao = McaRoomDatabase.get(application).videoWorkflowDao(),
    )

    private val publisher = VideoMediaPublisher(application, store)

    private val importAdapter = VideoImportAdapter(store)

    /**
     * Chat/engine lifecycle lease provider installed by the app composition root before the first
     * page access. The adapter acquires this BEFORE the worker's file gate (fixed lock order:
     * admission → chat lease → physical media lock), so a video run can never race a loaded chat
     * model or a second native task.
     */
    @Volatile
    private var runtimeLeaseProvider: (suspend () -> com.muyuchat.core.engine.EngineLifecycleLeaseHandle)? = null

    private val workerAdapter = LocalVideoWorkerAdapter(
        context = application,
        scope = scope,
        publisher = publisher,
        acquireRuntimeLease = { runtimeLeaseProvider?.invoke() },
    )

    private val remoteAdapter = RemoteComfyExecutionAdapter(
        context = application,
        publisher = publisher,
        onRemotePromptId = { runId, attemptId, promptId ->
            store.recordRemotePromptId(runId, attemptId, promptId, System.currentTimeMillis())
        },
    )

    internal fun installRuntimeLeaseProvider(
        provider: suspend () -> com.muyuchat.core.engine.EngineLifecycleLeaseHandle,
    ) {
        check(!created.get()) { "runtime lease must be installed before the video owner is created" }
        runtimeLeaseProvider = provider
    }

    private val ownerInstance: VideoGenerationOwner by lazy {
        check(created.compareAndSet(false, true)) {
            "VideoHostAssembly must host exactly one owner per process (second construction is a bug)"
        }
        VideoGenerationOwner(
            scope = scope,
            store = store,
            executor = workerAdapter,
            remoteExecutor = remoteAdapter,
            mediaPublisher = publisher,
            ids = { "v-${UUID.randomUUID()}" },
        )
    }

    /** The single typed port handed to UI/API/worker callers. */
    val port: VideoWorkflowPort get() = ownerInstance

    /**
     * Absolute paths of the manifest-resolved Wan components. The Local API accepts them only as
     * explicit confirmations: a request may restate the files this device will actually load, and
     * anything else is rejected instead of silently injecting an arbitrary path into the worker.
     */
    val localModelPaths: VideoLocalModelPaths
        get() {
            val root = File(appContext.filesDir, "video_models/${Wan22Ti2v5bProfiles.MODEL_ID}")
            val byRole = Wan22Ti2v5bProfiles.Q4_0_Q4_K_M.components.associateBy { it.role }
            return VideoLocalModelPaths(
                modelRoot = root.absolutePath,
                ditPath = File(
                    root,
                    byRole.getValue(VideoModelComponentRole.DIT).relativePath,
                ).absolutePath,
                textEncoderPath = File(
                    root,
                    byRole.getValue(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL).relativePath,
                ).absolutePath,
                vaePath = File(
                    root,
                    byRole.getValue(VideoModelComponentRole.VAE_WAN22_5B).relativePath,
                ).absolutePath,
            )
        }

    /** Published media of one run: read-only evidence, never a second state writer. */
    fun mediaByRun(runId: String): List<VideoMediaRecord> = store.mediaByRun(runId)

    /** Actual external backend / fallback evidence reported by the worker for one run. */
    fun effectiveExecutionDetail(runId: String): String? = ownerInstance.effectiveExecutionDetail(runId)

    /** ComfyUI API-format import capability for the video feature. */
    val importPort: com.muyuchat.core.video.contract.VideoImportPort get() = importAdapter

    /** Explicit remote endpoint probe used by the video feature (V3-01). */
    val remoteProbePort: com.muyuchat.core.video.contract.VideoRemoteProbePort get() = remoteAdapter

    /**
     * Process-scoped acquisition owner for the Wan2.2 components. It writes to the same model
     * root the worker adapter reads, and its lifetime is the runtime root so leaving the video
     * page cannot cancel a transfer.
     */
    private val modelAssetOwner by lazy {
        VideoModelAssetOwner(
            root = File(application.filesDir, "video_models/${Wan22Ti2v5bProfiles.MODEL_ID}"),
            scope = scope,
        )
    }

    val modelAssetPort: VideoModelAssetPort get() = modelAssetOwner
}

/**
 * Manifest-resolved absolute component paths for the pinned Wan TI2V-5B model.
 *
 * These are device-local execution facts, exposed only so the authenticated Local API can confirm
 * that a request naming explicit paths refers to the same files this device will load.
 */
internal data class VideoLocalModelPaths(
    val modelRoot: String,
    val ditPath: String,
    val textEncoderPath: String,
    val vaePath: String,
)

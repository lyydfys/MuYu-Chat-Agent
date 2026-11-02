package com.muyuchat.mca.video.execution

import com.muyuchat.core.video.contract.VideoAttemptId
import com.muyuchat.core.video.contract.VideoCancelResult
import com.muyuchat.core.video.contract.VideoMediaAssetId
import com.muyuchat.core.video.contract.VideoMediaProjection
import com.muyuchat.core.video.contract.VideoDeleteResult
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoRequestId
import com.muyuchat.core.video.contract.VideoRunId
import com.muyuchat.core.video.contract.VideoRunProjection
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.core.video.contract.VideoRunTransitions
import com.muyuchat.core.video.contract.VideoResourceState
import com.muyuchat.core.video.contract.VideoRemoteEndpointBinding
import com.muyuchat.core.video.contract.VideoSubmitCommand
import com.muyuchat.core.video.contract.VideoSubmitResult
import com.muyuchat.core.video.contract.VideoWorkflowPort
import com.muyuchat.mca.video.data.VideoMediaRecord
import com.muyuchat.mca.video.data.VideoRunStore
import com.muyuchat.mca.video.media.VideoMediaPublisher
import com.muyuchat.mca.video.workflow.VideoWorkflowCompiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single process-level owner of video generation (proposal §3.2/§4.3/§7.1). UI, Local API
 * and workers all reach video through this class via [VideoWorkflowPort]; it is the ONLY writer
 * of video run state, applying every decision through the store's conditional
 * (runId+attemptId+stateVersion) transactions and the frozen contract transition table.
 *
 * Execution is delegated to an injected [VideoExecutionAdapter] — the `:local_video` worker
 * adapter lands in V1-02b; until then `executor == null` means the page's submit is honestly
 * rejected with BACKEND_UNAVAILABLE (never a fake queue). Tests drive the full state machine
 * with a fake adapter; fake outcomes never count as production capability.
 *
 * Leaving a page cancels nothing: the owner lives in the process runtime root scope, and
 * startup reconciliation marks every non-terminal run from a previous process INTERRUPTED
 * (never "still sampling", never auto-resubmitted).
 */
class VideoGenerationOwner(
    private val scope: CoroutineScope,
    private val store: VideoRunStore,
    private val executor: VideoExecutionAdapter?,
    private val remoteExecutor: VideoExecutionAdapter? = null,
    private val mediaPublisher: VideoMediaPublisher? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String,
) : VideoWorkflowPort {

    /** Narrow seam the V1-02b worker adapter (and only tests otherwise) implement. */
    interface VideoExecutionAdapter {
        /**
         * Execute one frozen snapshot; cooperate with cancellation; report real stages only.
         * [reportStage] is a suspend call delivered IN the executing coroutine: stage advances
         * and the final outcome stay ordered, exactly like the worker's marshaled callbacks.
         */
        suspend fun execute(
            request: VideoExecutionRequest,
            reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
        ): VideoExecutionOutcome

        /** Best-effort physical stop; the owner only reports CANCELLED after confirmation. */
        fun cancel(requestId: VideoRequestId)
    }

    private val mutex = Mutex()
    private val projections = MutableStateFlow<List<VideoRunProjection>>(emptyList())
    private val mediaProjections = MutableStateFlow<List<VideoMediaProjection>>(emptyList())
    private val activeJobs = HashMap<VideoRunId, Job>()

    /**
     * Execution evidence reported by the worker for one run (JSON with `externalBackend` and
     * `fallback`). In-process diagnostic retention for the authenticated API; it is not a
     * business state and is deliberately not persisted.
     */
    private val effectiveExecutionDetails = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Evidence for a run this process executed; null when none was reported. */
    fun effectiveExecutionDetail(runId: String): String? = effectiveExecutionDetails[runId]

    /**
     * Snapshot of rows left by a PREVIOUS process, captured synchronously before this owner can
     * accept a submit. Reconciliation must never touch a run admitted by this process, even if the
     * coroutine happens to run after that submit.
     */
    private val startupRows: List<com.muyuchat.mca.video.data.VideoRunRow> = store.nonTerminalRuns()

    init {
        // Reconcile filesystem journals BEFORE marking leftover runs interrupted, so a publish
        // that was renamed but not committed can be decided from persisted evidence.
        scope.launch {
            mediaPublisher?.reconcile()
            reconcileStartupState(startupRows)
            refreshMediaProjections()
        }
    }

    private suspend fun reconcileStartupState(rows: List<com.muyuchat.mca.video.data.VideoRunRow>) {
        rows.forEach { previous ->
            val row = store.runById(previous.runId) ?: return@forEach
            // A new attempt/submit or a concurrent writer moved the row: not this owner's to settle.
            if (row.attemptId != previous.attemptId || row.stateVersion != previous.stateVersion) return@forEach
            val allowed = VideoRunTransitions.allows(
                com.muyuchat.core.video.contract.VideoRunState.valueOf(row.state),
                VideoRunState.INTERRUPTED,
            )
            if (allowed) {
                store.transition(
                    runId = row.runId,
                    attemptId = row.attemptId,
                    expectedVersion = row.stateVersion,
                    fromState = row.state,
                    toState = VideoRunState.INTERRUPTED,
                    stage = row.stage,
                    resourceState = VideoResourceState.UNCONFIRMED,
                    errorCode = VideoErrorCode.INTERRUPTED_PROCESS_DEATH,
                    errorDetail = "进程重启时任务仍在 ${row.state}，已按中断结算",
                )
            }
        }
        refreshProjections()
    }

    private suspend fun refreshProjections() {
        projections.value = store.recentRuns(limit = 50).map { it.toProjection() }
    }

    override fun observeRuns(): Flow<List<VideoRunProjection>> = projections.asStateFlow()

    override fun observeRun(runId: VideoRunId): Flow<VideoRunProjection?> =
        projections.asStateFlow().map { list -> list.firstOrNull { it.runId == runId } }

    override fun observeMedia(): Flow<List<VideoMediaProjection>> = mediaProjections.asStateFlow()

    override suspend fun deleteMedia(mediaId: VideoMediaAssetId): VideoDeleteResult {
        val publisher = mediaPublisher
            ?: return VideoDeleteResult.Failed(mediaId, VideoErrorCode.BACKEND_UNAVAILABLE, "媒体发布链未装配")
        val result = publisher.deleteOwnedMedia(mediaId)
        refreshMediaProjections()
        return result
    }

    private fun refreshMediaProjections() {
        val publisher = mediaPublisher ?: return
        mediaProjections.value = store.recentMedia(limit = 100).mapNotNull { row ->
            runCatching { publisher.projection(row) }.getOrNull()
        }
    }

    override suspend fun saveRevision(document: com.muyuchat.core.video.contract.VideoWorkflowDocument): com.muyuchat.core.video.contract.VideoRevisionResult {
        val stored = store.saveRevision(document, clock())
        return if (stored != null) {
            com.muyuchat.core.video.contract.VideoRevisionResult.Saved(
                com.muyuchat.core.video.contract.VideoRevisionId(stored),
            )
        } else {
            com.muyuchat.core.video.contract.VideoRevisionResult.Conflict(document.revisionId)
        }
    }

    override suspend fun submit(command: VideoSubmitCommand): VideoSubmitResult = mutex.withLock {
        val revision = store.revisionById(command.revisionId.value)
            ?: return VideoSubmitResult.Rejected(
                VideoErrorCode.WORKFLOW_INVALID,
                "修订 ${command.revisionId.value} 不存在或未保存",
            )
        val document = store.decodeRevisionDocument(revision)
            ?: return VideoSubmitResult.Rejected(
                VideoErrorCode.WORKFLOW_INVALID,
                "修订 ${command.revisionId.value} 的图无法解析",
            )
        val manifest = store.manifestFor(command.backend)
            ?: return VideoSubmitResult.Rejected(
                VideoErrorCode.MODEL_MISSING,
                "当前后端 ${command.backend} 未绑定模型清单",
            )
        // Seed authority: an explicit retry override wins, then the draft's own conditioning seed
        // (the UI labels 0 as "random at submit"), then a fresh random draw. Without the middle
        // step the editable 种子 field was cosmetic on a fresh submit, so a controlled A/B
        // (same seed, different prompt) was impossible through the production UI.
        val seedPolicy = command.seedOverride?.let { VideoWorkflowCompiler.SeedPolicy.Fixed(it) }
            ?: draftSeedOf(document)?.takeIf { it != 0L }?.let { VideoWorkflowCompiler.SeedPolicy.Fixed(it) }
            ?: VideoWorkflowCompiler.SeedPolicy.Randomized(drawnSeed = store.nextSeed())
        val compiled = VideoWorkflowCompiler.compile(
            document,
            manifest,
            seedPolicy,
            command.inputBindings,
            backend = command.backend,
            engine = command.engine,
            externalBackend = command.externalBackend,
            loraSpecs = command.loraSpecs,
        )
        if (compiled is VideoWorkflowCompiler.Result.Rejected) {
            return VideoSubmitResult.Rejected(compiled.firstCode(), compiled.issues.firstOrNull()?.detail ?: "工作流不可编译")
        }
        val snapshot = (compiled as VideoWorkflowCompiler.Result.Compiled).snapshot
        val target = when (command.backend) {
            com.muyuchat.core.video.contract.VideoBackendKind.LOCAL_NATIVE -> {
                if (command.remoteBinding != null) {
                    return VideoSubmitResult.Rejected(
                        VideoErrorCode.INPUT_INVALID,
                        "本地后端不得携带远端 endpoint 或凭据",
                    )
                }
                executor ?: return VideoSubmitResult.Rejected(
                    VideoErrorCode.BACKEND_UNAVAILABLE,
                    "视频执行链尚未接入（owner 已冻结快照并拒绝排队，不伪造进度）",
                )
            }

            com.muyuchat.core.video.contract.VideoBackendKind.REMOTE_COMFY -> {
                if (command.remoteBinding == null) {
                    return VideoSubmitResult.Rejected(
                        VideoErrorCode.INPUT_INVALID,
                        "远端后端必须显式提供 endpoint；本地失败不会自动切换",
                    )
                }
                if (document.rawSourceRef.isNullOrBlank()) {
                    return VideoSubmitResult.Rejected(
                        VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
                        "该修订没有保存 ComfyUI API-format 原始图，不能提交到远端",
                    )
                }
                remoteExecutor ?: return VideoSubmitResult.Rejected(
                    VideoErrorCode.BACKEND_UNAVAILABLE,
                    "远端 ComfyUI 执行链尚未装配",
                )
            }

            com.muyuchat.core.video.contract.VideoBackendKind.FAKE -> {
                return VideoSubmitResult.Rejected(VideoErrorCode.BACKEND_UNAVAILABLE, "FAKE 后端不可用于生产提交")
            }
        }

        val runId = VideoRunId(ids())
        val attemptId = VideoAttemptId(ids())
        val requestId = VideoRequestId(ids())
        val now = clock()
        val inserted = store.insertRun(
            runId = runId.value,
            attemptId = attemptId.value,
            requestId = requestId.value,
            snapshot = snapshot,
            backend = command.backend,
            state = VideoRunState.QUEUED,
            nowMs = now,
            remoteEndpointId = command.remoteBinding?.endpointId,
        )
        if (!inserted) {
            return VideoSubmitResult.Rejected(VideoErrorCode.INPUT_INVALID, "任务写入失败（runId 冲突）")
        }
        store.appendEvent(runId.value, attemptId.value, VideoRunState.QUEUED, VideoRunStage.NONE, null, now)
        launchAttempt(runId, attemptId, requestId, snapshot, document, target, command.remoteBinding)
        refreshProjections()
        return VideoSubmitResult.Admitted(runId, attemptId)
    }

    override suspend fun requestCancel(runId: VideoRunId): VideoCancelResult = mutex.withLock {
        val row = store.runById(runId.value) ?: return VideoCancelResult.UnknownRun(runId)
        val current = VideoRunState.valueOf(row.state)
        if (VideoRunTransitions.isTerminal(current)) {
            return VideoCancelResult.AlreadyTerminal(runId, current)
        }
        if (current == VideoRunState.CANCEL_REQUESTED) {
            // Already recorded; idempotent accept (still awaiting physical stop).
            return VideoCancelResult.Accepted(runId)
        }
        // A queued run holds no worker/resource yet: the table settles it directly CANCELLED.
        if (current == VideoRunState.QUEUED && VideoRunTransitions.allows(current, VideoRunState.CANCELLED)) {
            val now = clock()
            val applied = store.transition(
                runId = row.runId,
                attemptId = row.attemptId,
                expectedVersion = row.stateVersion,
                fromState = row.state,
                toState = VideoRunState.CANCELLED,
                stage = row.stage,
                resourceState = VideoResourceState.RELEASED,
                errorCode = null,
                errorDetail = "排队中取消，未分配执行资源",
            )
            if (applied > 0) {
                store.appendEvent(row.runId, row.attemptId, VideoRunState.CANCELLED, VideoRunStage.NONE, "排队中取消", now)
                activeJobs.remove(runId)?.cancel()
                refreshProjections()
                return VideoCancelResult.Accepted(runId)
            }
            // Stale write: report the fresh state without re-entering the mutex.
            val fresh = store.runById(runId.value)
            return if (fresh != null) {
                val freshState = VideoRunState.valueOf(fresh.state)
                if (VideoRunTransitions.isTerminal(freshState)) {
                    VideoCancelResult.AlreadyTerminal(runId, freshState)
                } else {
                    VideoCancelResult.Accepted(runId)
                }
            } else {
                VideoCancelResult.UnknownRun(runId)
            }
        }
        if (!VideoRunTransitions.allows(current, VideoRunState.CANCEL_REQUESTED)) {
            return VideoCancelResult.AlreadyTerminal(runId, current)
        }
        val now = clock()
        val applied = store.transition(
            runId = row.runId,
            attemptId = row.attemptId,
            expectedVersion = row.stateVersion,
            fromState = row.state,
            toState = VideoRunState.CANCEL_REQUESTED,
            stage = row.stage,
            resourceState = VideoResourceState.STOPPING,
            errorCode = VideoErrorCode.CANCEL_REQUESTED,
            errorDetail = null,
        )
        if (applied == 0) {
            val fresh = store.runById(runId.value)
            return if (fresh != null && VideoRunTransitions.isTerminal(VideoRunState.valueOf(fresh.state))) {
                VideoCancelResult.AlreadyTerminal(runId, VideoRunState.valueOf(fresh.state))
            } else {
                VideoCancelResult.Accepted(runId) // another writer already recorded the request
            }
        }
        store.appendEvent(row.runId, row.attemptId, VideoRunState.CANCEL_REQUESTED, VideoRunStage.NONE, "用户请求取消", now)
        // Cooperative stop ONLY: the adapter confirms physical stop by returning Stopped; killing
        // the attempt coroutine here would interrupt the adapter mid-flight and lose that
        // confirmation (the coroutine itself is only a watchdog for scope shutdown). Remote runs
        // use their own adapter and never fall back to the local one.
        val cancelTarget = if (row.backend == com.muyuchat.core.video.contract.VideoBackendKind.REMOTE_COMFY.name) {
            remoteExecutor
        } else {
            executor
        }
        cancelTarget?.cancel(VideoRequestId(row.requestId))
        refreshProjections()
        return VideoCancelResult.Accepted(runId)
    }

    override suspend fun retry(runId: VideoRunId, command: com.muyuchat.core.video.contract.VideoRetryCommand): VideoSubmitResult {
        val row = store.runById(runId.value)
            ?: return VideoSubmitResult.Rejected(VideoErrorCode.INPUT_MISSING, "原任务不存在")
        if (!VideoRunTransitions.isTerminal(VideoRunState.valueOf(row.state))) {
            return VideoSubmitResult.Rejected(VideoErrorCode.INPUT_INVALID, "原任务尚未终态，不能重试")
        }
        if (row.backend == com.muyuchat.core.video.contract.VideoBackendKind.REMOTE_COMFY.name) {
            return VideoSubmitResult.Rejected(
                VideoErrorCode.INPUT_INVALID,
                "远端任务重试必须重新显式提供 endpoint 与凭据（凭据从不持久化）",
            )
        }
        val snapshot = store.decodeSnapshot(row.snapshotJson, command.newSeed)
            ?: return VideoSubmitResult.Rejected(VideoErrorCode.INPUT_INVALID, "原任务快照无法恢复")
        return submit(
            VideoSubmitCommand(
                revisionId = com.muyuchat.core.video.contract.VideoRevisionId(row.revisionId),
                inputBindings = snapshot.inputRefs,
                backend = com.muyuchat.core.video.contract.VideoBackendKind.valueOf(row.backend),
                seedOverride = snapshot.resolvedParams.seed,
                // A retry re-runs the SAME frozen execution choice; silently falling back to the
                // built-in engine or a different external backend would make the snapshot a lie.
                engine = snapshot.resolvedParams.engine,
                externalBackend = snapshot.resolvedParams.externalBackend,
            ),
        )
    }

    private fun launchAttempt(
        runId: VideoRunId,
        attemptId: VideoAttemptId,
        requestId: VideoRequestId,
        snapshot: com.muyuchat.core.video.contract.VideoRunSnapshot,
        document: com.muyuchat.core.video.contract.VideoWorkflowDocument,
        target: VideoExecutionAdapter,
        remoteBinding: VideoRemoteEndpointBinding?,
    ) {
        val job = scope.launch {
            try {
                val enteredExecution = advance(runId, attemptId, VideoRunState.QUEUED, VideoRunState.PREPARING, VideoRunStage.PREPARE_INPUTS)
                if (!enteredExecution) {
                    // The run was settled (e.g. queued-cancel won the race) before this attempt
                    // began executing; executing anyway would contradict the terminal state.
                    return@launch
                }
                val request = VideoExecutionRequest(
                    runId = runId,
                    attemptId = attemptId,
                    requestId = requestId,
                    snapshot = snapshot,
                    document = document,
                    remoteBinding = remoteBinding,
                )
                val outcome = try {
                    target.execute(
                        request,
                        reportStage = { stage, step, stepTotal ->
                            advanceToStagePhase(runId, attemptId, stage)
                            store.updateProgress(runId.value, attemptId.value, stage, step, stepTotal, clock())
                            refreshProjections()
                        },
                    )
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    VideoExecutionOutcome.Failed(
                        VideoErrorCode.NATIVE_EXECUTION_FAILED,
                        "执行适配器异常：${failure.javaClass.simpleName}",
                    )
                }
                settleOutcome(runId, attemptId, outcome)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // The attempt's coroutine was cancelled (user cancel path). Physical stop is the
                // adapter's duty; this process marks INTERRUPTED unless cancel already settled.
                val row = store.runById(runId.value)
                if (row != null && row.attemptId == attemptId.value) {
                    val state = VideoRunState.valueOf(row.state)
                    if (!VideoRunTransitions.isTerminal(state)) {
                        store.transition(
                            runId.value, attemptId.value, row.stateVersion, row.state,
                            VideoRunState.INTERRUPTED, row.stage,
                            VideoResourceState.UNCONFIRMED,
                            VideoErrorCode.INTERRUPTED_PROCESS_DEATH, "执行协程被取消且未确认物理停止",
                        )
                    }
                }
                refreshProjections()
                throw cancellation
            } finally {
                mutex.withLock { activeJobs.remove(runId) }
            }
        }
        activeJobs[runId] = job
    }

    private suspend fun advance(
        runId: VideoRunId,
        attemptId: VideoAttemptId,
        from: VideoRunState,
        to: VideoRunState,
        stage: VideoRunStage,
    ): Boolean {
        val row = store.runById(runId.value) ?: return false
        if (row.attemptId != attemptId.value) return false // stale attempt
        val current = VideoRunState.valueOf(row.state)
        if (current != from) return false // stale writer
        store.transition(
            runId.value, attemptId.value, row.stateVersion, row.state, to, stage.name,
            VideoResourceState.HELD, null, null,
        )
        store.appendEvent(runId.value, attemptId.value, to, stage, null, clock())
        refreshProjections()
        return true
    }

    /**
     * Stage callbacks also advance the BUSINESS state (proposal §4.3.1): TEXT_ENCODE =>
     * CONDITIONING, SAMPLE => SAMPLING, DECODE => DECODING, ENCODE => ENCODING, PUBLISH =>
     * PUBLISHING. Best-effort and conditional: a stale or out-of-order callback changes nothing,
     * and an illegal jump (e.g. PREPARING straight to COMPLETED) is refused by the frozen table —
     * the adapter walks the real phases because the real pipeline really has them.
     */
    private suspend fun advanceToStagePhase(runId: VideoRunId, attemptId: VideoAttemptId, stage: VideoRunStage) {
        val target = when (stage) {
            VideoRunStage.TEXT_ENCODE -> VideoRunState.CONDITIONING
            VideoRunStage.SAMPLE -> VideoRunState.SAMPLING
            VideoRunStage.DECODE -> VideoRunState.DECODING
            VideoRunStage.ENCODE -> VideoRunState.ENCODING
            VideoRunStage.PUBLISH -> VideoRunState.PUBLISHING
            else -> null
        } ?: return
        val row = store.runById(runId.value) ?: return
        if (row.attemptId != attemptId.value) return
        val current = VideoRunState.valueOf(row.state)
        if (current == target || !VideoRunTransitions.allows(current, target)) return
        if (store.transition(
                row.runId, row.attemptId, row.stateVersion, row.state, target, stage.name,
                VideoResourceState.HELD, null, null,
            ) > 0
        ) {
            store.appendEvent(row.runId, row.attemptId, target, stage, null, clock())
            refreshProjections()
        }
    }

    private suspend fun settleOutcome(runId: VideoRunId, attemptId: VideoAttemptId, outcome: VideoExecutionOutcome) {
        val row = store.runById(runId.value) ?: return
        if (row.attemptId != attemptId.value) return
        val current = VideoRunState.valueOf(row.state)
        when (outcome) {
            is VideoExecutionOutcome.Succeeded ->
                settleSucceeded(row, runId, attemptId, outcome.outputRelativePath, outcome.executionDetail)
            is VideoExecutionOutcome.Failed -> {
                val target = if (current == VideoRunState.CANCEL_REQUESTED) VideoRunState.CANCELLED else VideoRunState.FAILED
                transitionOutcome(row, target, VideoResourceState.RELEASED, outcome.code, outcome.detail)
            }

            is VideoExecutionOutcome.Stopped -> {
                // A Stopped outcome only settles CANCELLED when the owner itself recorded the
                // cancel request first. An unrequested stop (worker-side death/abort) is a real
                // failure, never silently ignored and never labeled a user cancellation.
                if (current == VideoRunState.CANCEL_REQUESTED) {
                    val remote = row.backend == com.muyuchat.core.video.contract.VideoBackendKind.REMOTE_COMFY.name
                    transitionOutcome(
                        row,
                        VideoRunState.CANCELLED,
                        VideoResourceState.RELEASED,
                        VideoErrorCode.CANCELLED_AFTER_NATIVE_STOP,
                        if (remote) {
                            "本机已停止等待；远端任务可能仍在运行（未确认远端取消）"
                        } else {
                            "native 已停止且资源已释放"
                        },
                    )
                } else {
                    transitionOutcome(
                        row,
                        VideoRunState.FAILED,
                        VideoResourceState.RELEASED,
                        VideoErrorCode.NATIVE_EXECUTION_FAILED,
                        "执行器在未收到取消请求时停止",
                    )
                }
            }

            is VideoExecutionOutcome.Interrupted -> {
                transitionOutcome(
                    row,
                    VideoRunState.INTERRUPTED,
                    VideoResourceState.UNCONFIRMED,
                    VideoErrorCode.INTERRUPTED_PROCESS_DEATH,
                    outcome.detail,
                )
            }
        }
        // Late success after a recorded cancel request is settled as CANCELLED (never deletes
        // confirmed output); settleSucceeded handles that before publishing.
        if (current == VideoRunState.CANCEL_REQUESTED && outcome is VideoExecutionOutcome.Succeeded) {
            transitionOutcome(
                row,
                VideoRunState.CANCELLED,
                VideoResourceState.RELEASED,
                VideoErrorCode.CANCELLED_AFTER_NATIVE_STOP,
                "取消请求先于发布提交，未发布成功媒体",
            )
        }
    }

    private suspend fun settleSucceeded(
        row: com.muyuchat.mca.video.data.VideoRunRow,
        runId: VideoRunId,
        attemptId: VideoAttemptId,
        outputRelativePath: String?,
        executionDetail: String? = null,
    ) {
        // Retain the worker's execution evidence (actual external backend / fallback marker) for
        // the authenticated API response. Diagnostic only: it is never a second business state.
        executionDetail?.takeIf { it.isNotBlank() }?.let { effectiveExecutionDetails[runId.value] = it }
        if (row.state == VideoRunState.CANCEL_REQUESTED.name) return // handled by settleOutcome
        // The worker walks the real stages; success without reaching ENCODING is a contract
        // violation, never a reason to fabricate the missing phases. PUBLISHING is accepted here
        // because a reported PUBLISH stage advances the business state before the final outcome.
        if (row.state != VideoRunState.ENCODING.name && row.state != VideoRunState.PUBLISHING.name) {
            transitionOutcome(
                row,
                VideoRunState.FAILED,
                VideoResourceState.RELEASED,
                VideoErrorCode.NATIVE_EXECUTION_FAILED,
                "执行器在未到达编码阶段时报告成功（阶段链不完整）",
            )
            return
        }
        if (row.state == VideoRunState.ENCODING.name) {
            advanceToStagePhase(runId, attemptId, VideoRunStage.PUBLISH)
        }
        val publishingRow = store.runById(runId.value) ?: return
        if (publishingRow.state != VideoRunState.PUBLISHING.name) {
            transitionOutcome(
                publishingRow,
                VideoRunState.FAILED,
                VideoResourceState.RELEASED,
                VideoErrorCode.PUBLISH_FAILED,
                "无法进入发布阶段",
            )
            return
        }
        val publisher = mediaPublisher
        if (publisher == null || outputRelativePath == null) {
            // Test/legacy assembly without a publisher: no media row is invented.
            transitionOutcome(
                publishingRow,
                VideoRunState.COMPLETED,
                VideoResourceState.RELEASED,
                null,
                null,
            )
            return
        }
        val snapshot = store.decodeSnapshot(publishingRow.snapshotJson, null)
        if (snapshot == null) {
            transitionOutcome(
                publishingRow,
                VideoRunState.FAILED,
                VideoResourceState.RELEASED,
                VideoErrorCode.PUBLISH_FAILED,
                "运行快照无法恢复，拒绝发布",
            )
            return
        }
        val result = publisher.publish(
            runId = runId.value,
            attemptId = attemptId.value,
            expectedVersion = publishingRow.stateVersion,
            params = snapshot.resolvedParams,
            shouldAbort = { store.runById(runId.value)?.state == VideoRunState.CANCEL_REQUESTED.name },
        )
        when (result) {
            is VideoMediaPublisher.PublishResult.Published -> {
                val committed = store.completeRunWithMedia(
                    runId = runId.value,
                    attemptId = attemptId.value,
                    expectedVersion = publishingRow.stateVersion,
                    fromState = VideoRunState.PUBLISHING.name,
                    media = result.record,
                    nowMs = clock(),
                )
                if (committed) {
                    store.appendEvent(
                        runId.value,
                        attemptId.value,
                        VideoRunState.COMPLETED,
                        VideoRunStage.FINALIZE,
                        "已发布媒体 ${result.record.mediaId}",
                        clock(),
                    )
                    publisher.finalizePublish(runId.value, result.record)
                    refreshProjections()
                    refreshMediaProjections()
                } else {
                    val fresh = store.runById(runId.value)
                    val freshState = fresh?.state?.let { runCatching { VideoRunState.valueOf(it) }.getOrNull() }
                    if (freshState == VideoRunState.CANCEL_REQUESTED && fresh != null) {
                        transitionOutcome(
                            fresh,
                            VideoRunState.CANCELLED,
                            VideoResourceState.RELEASED,
                            VideoErrorCode.CANCELLED_AFTER_NATIVE_STOP,
                            "发布提交前取消已生效；文件保留为待对账隔离项",
                        )
                    } else if (freshState != null && fresh != null && !VideoRunTransitions.isTerminal(freshState)) {
                        transitionOutcome(
                            fresh,
                            VideoRunState.FAILED,
                            VideoResourceState.RELEASED,
                            VideoErrorCode.PUBLISH_FAILED,
                            "运行状态已变化，媒体事务未提交",
                        )
                    }
                }
            }

            VideoMediaPublisher.PublishResult.Cancelled -> {
                val fresh = store.runById(runId.value) ?: return
                val state = VideoRunState.valueOf(fresh.state)
                if (state == VideoRunState.CANCEL_REQUESTED) {
                    transitionOutcome(
                        fresh,
                        VideoRunState.CANCELLED,
                        VideoResourceState.RELEASED,
                        VideoErrorCode.CANCELLED_AFTER_NATIVE_STOP,
                        "取消请求先于发布提交，已清理暂存媒体",
                    )
                }
            }

            is VideoMediaPublisher.PublishResult.Failed -> {
                transitionOutcome(
                    publishingRow,
                    VideoRunState.FAILED,
                    VideoResourceState.RELEASED,
                    result.code,
                    result.detail,
                )
            }
        }
    }

    /**
     * The draft's own conditioning seed, or null when the document has no seed parameter.
     *
     * The UI convention (see the 种子 field hint) is that 0 means "random at submit"; any other
     * value is a user-chosen seed that the run must use verbatim so a run can be reproduced.
     */
    private fun draftSeedOf(document: com.muyuchat.core.video.contract.VideoWorkflowDocument): Long? =
        document.nodes
            .firstOrNull { it.typeId == VideoNodeTypeId.WAN_CONDITIONING.typeId }
            ?.params?.get("seed")
            ?.let { (it as? VideoNodeValue.LongValue)?.value }

    private suspend fun transitionOutcome(
        row: com.muyuchat.mca.video.data.VideoRunRow,
        target: VideoRunState,
        resourceState: VideoResourceState,
        code: VideoErrorCode?,
        detail: String?,
    ) {
        val current = VideoRunState.valueOf(row.state)
        if (current == target) return
        if (!VideoRunTransitions.allows(current, target)) return
        val applied = store.transition(
            row.runId, row.attemptId, row.stateVersion, row.state, target, row.stage,
            resourceState, code, detail,
        )
        if (applied > 0) {
            store.appendEvent(row.runId, row.attemptId, target, VideoRunStage.FINALIZE, detail, clock())
            if (target == VideoRunState.FAILED || target == VideoRunState.CANCELLED) {
                mediaPublisher?.abandon(row.runId)
            }
            refreshProjections()
        }
    }
}

/** Frozen request handed to the execution adapter (worker in V1-02b). */
data class VideoExecutionRequest(
    val runId: VideoRunId,
    val attemptId: VideoAttemptId,
    val requestId: VideoRequestId,
    val snapshot: com.muyuchat.core.video.contract.VideoRunSnapshot,
    /** The immutable revision this attempt executes; prompt text stays out of run snapshots. */
    val document: com.muyuchat.core.video.contract.VideoWorkflowDocument,
    /** Transient remote endpoint/credential binding; null for local native runs. */
    val remoteBinding: VideoRemoteEndpointBinding? = null,
)

sealed class VideoExecutionOutcome {
    data class Succeeded(
        val outputRelativePath: String?,
        /** Execution evidence from the worker (actual external backend, fallback marker). */
        val executionDetail: String? = null,
    ) : VideoExecutionOutcome()

    data class Failed(val code: VideoErrorCode, val detail: String?) : VideoExecutionOutcome()

    /** Physical stop AND release both confirmed by the adapter. */
    data object Stopped : VideoExecutionOutcome()

    /** Worker/binder death or scope loss: stop could not be confirmed; never claim released. */
    data class Interrupted(val detail: String?) : VideoExecutionOutcome()
}

package com.muyuchat.mca.video.execution

import com.muyuchat.core.video.contract.VideoBackendKind
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoInputBinding
import com.muyuchat.core.video.contract.VideoPortKind
import com.muyuchat.core.video.contract.VideoRunId
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.core.video.contract.VideoRunTransitions
import com.muyuchat.core.video.contract.VideoResourceState
import com.muyuchat.core.video.contract.VideoSubmitCommand
import com.muyuchat.core.video.contract.VideoSubmitResult
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.mca.video.data.VideoRevisionRecord
import com.muyuchat.mca.video.data.VideoRunRow
import com.muyuchat.mca.video.data.VideoRunStore
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import com.muyuchat.feature.video.workspace.VideoWorkflowTemplates
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner state-machine behavior on a pure JVM fake store + fake adapter (task V1-02a exit):
 * honest refusal without an executor, happy path, queued cancel, cancel-during-execution with
 * confirmed stop, late-success-after-cancel, failure settlement, stale-attempt progress,
 * startup INTERRUPT reconciliation and retry-as-new-run.
 *
 * FAKE ADAPTER OUTCOMES PROVE STATE MACHINE BEHAVIOR ONLY — they are not evidence of any real
 * video generation capability (that is P0-C / V1-03 on a real device).
 */
class VideoGenerationOwnerTest {

    private class FakeStore : VideoRunStore {
        val revisions = LinkedHashMap<String, Pair<VideoWorkflowDocument, String>>() // id -> (doc, digest)
        val runs = LinkedHashMap<String, VideoRunRow>()
        val events = mutableListOf<Triple<String, String, String>>() // runId, attempt, state
        val snapshots = LinkedHashMap<String, com.muyuchat.core.video.contract.VideoRunSnapshot>()
        val seeds = AtomicLong(1000)
        var failInsert = false

        override fun revisionById(revisionId: String): VideoRevisionRecord? =
            revisions[revisionId]?.let { (doc, digest) ->
                VideoRevisionRecord(doc.revisionId.value, doc.workflowId.value, doc.source.name, doc.schemaVersion, "{}", digest)
            }

        override fun decodeRevisionDocument(revision: VideoRevisionRecord): VideoWorkflowDocument? =
            revisions[revision.revisionId]?.first

        override fun manifestFor(backend: VideoBackendKind) = when (backend) {
            VideoBackendKind.LOCAL_NATIVE, VideoBackendKind.REMOTE_COMFY -> Wan22Ti2v5bProfiles.Q4_0_Q8_0
            else -> null
        }

        override fun nextSeed(): Long = seeds.incrementAndGet()

        override fun runById(runId: String): VideoRunRow? = runs[runId]

        override fun nonTerminalRuns(): List<VideoRunRow> =
            runs.values.filter { !VideoRunTransitions.isTerminal(VideoRunState.valueOf(it.state)) }

        override fun recentRuns(limit: Int): List<VideoRunRow> = runs.values.sortedByDescending { it.createdAt }

        override fun insertRun(
            runId: String,
            attemptId: String,
            requestId: String,
            snapshot: com.muyuchat.core.video.contract.VideoRunSnapshot,
            backend: VideoBackendKind,
            state: VideoRunState,
            nowMs: Long,
            remoteEndpointId: String?,
        ): Boolean {
            if (failInsert || runs.containsKey(runId)) return false
            snapshots[runId] = snapshot
            runs[runId] = VideoRunRow(
                runId, attemptId, requestId, 0, "", snapshot.workflowId.value, snapshot.revisionId.value,
                backend.name, state.name, VideoResourceState.HELD.name, VideoRunStage.NONE.name,
                null, null, null, null, null, remoteEndpointId, null, nowMs, nowMs, null,
            )
            return true
        }

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
        ): Int {
            val row = runs[runId] ?: return 0
            if (row.attemptId != attemptId || row.stateVersion != expectedVersion || row.state != fromState) return 0
            if (!VideoRunTransitions.allows(VideoRunState.valueOf(fromState), toState)) return 0
            runs[runId] = row.copy(
                stateVersion = row.stateVersion + 1,
                state = toState.name,
                stage = stage,
                resourceState = resourceState.name,
                errorCode = errorCode?.name,
                errorDetail = errorDetail,
                terminalAt = if (VideoRunTransitions.isTerminal(toState)) row.updatedAt + 1 else null,
                updatedAt = row.updatedAt + 1,
            )
            events += Triple(runId, attemptId, toState.name)
            return 1
        }

        override fun updateProgress(runId: String, attemptId: String, stage: VideoRunStage, step: Long?, stepTotal: Long?, nowMs: Long) {
            val row = runs[runId] ?: return
            if (row.attemptId != attemptId) return // stale attempt guard
            runs[runId] = row.copy(stage = stage.name, step = step, stepTotal = stepTotal, updatedAt = nowMs)
        }

        override fun appendEvent(runId: String, attemptId: String, state: VideoRunState, stage: VideoRunStage, message: String?, nowMs: Long) {
            events += Triple(runId, attemptId, state.name)
        }

        override fun decodeSnapshot(snapshotJson: String, seedOverride: Long?): com.muyuchat.core.video.contract.VideoRunSnapshot? =
            snapshots.values.firstOrNull()?.let { snap ->
                if (seedOverride == null) snap else snap.copy(resolvedParams = snap.resolvedParams.copy(seed = seedOverride))
            }

        override fun saveRevision(document: VideoWorkflowDocument, nowMs: Long): String? {
            val digest = com.muyuchat.core.video.contract.VideoWorkflowCanon.canonicalExecutableSha256(document)
            val existing = revisions[document.revisionId.value]
            return if (existing != null && existing.second != digest) {
                null
            } else {
                revisions[document.revisionId.value] = document to digest
                document.revisionId.value
            }
        }

        override fun recentMedia(limit: Int): List<com.muyuchat.mca.video.data.VideoMediaRecord> = emptyList()

        override fun allMedia(): List<com.muyuchat.mca.video.data.VideoMediaRecord> = emptyList()

        override fun mediaById(mediaId: String): com.muyuchat.mca.video.data.VideoMediaRecord? = null

        override fun mediaByRun(runId: String): List<com.muyuchat.mca.video.data.VideoMediaRecord> = emptyList()

        override fun recordRemotePromptId(runId: String, attemptId: String, promptId: String, nowMs: Long): Boolean = false

        override fun completeRunWithMedia(
            runId: String,
            attemptId: String,
            expectedVersion: Int,
            fromState: String,
            media: com.muyuchat.mca.video.data.VideoMediaRecord,
            nowMs: Long,
        ): Boolean = false

        override fun markMediaDeleted(mediaId: String, nowMs: Long): Boolean = false

        override fun deleteTombstonedMedia(mediaId: String): Int = 0
    }

    private class FakeAdapter(
        var script: suspend (VideoExecutionRequest, suspend (VideoRunStage, Long?, Long?) -> Unit, () -> Boolean) -> VideoExecutionOutcome,
    ) : VideoGenerationOwner.VideoExecutionAdapter {
        val started = CompletableDeferred<VideoExecutionRequest>()
        val cancelCalls = mutableListOf<String>()

        override suspend fun execute(
            request: VideoExecutionRequest,
            reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
        ): VideoExecutionOutcome {
            started.complete(request)
            return script(request, reportStage) { cancelCalls.isNotEmpty() }
        }

        override fun cancel(requestId: com.muyuchat.core.video.contract.VideoRequestId) {
            cancelCalls += requestId.value
        }
    }

    private fun TestScope.newOwner(
        store: FakeStore,
        adapter: VideoGenerationOwner.VideoExecutionAdapter?,
    ): VideoGenerationOwner {
        val owner = VideoGenerationOwner(
            scope = this.backgroundScope,
            store = store,
            executor = adapter,
            clock = { 1L },
            ids = { "v-${store.seeds.incrementAndGet()}" },
        )
        testScheduler.runCurrent()
        return owner
    }

    private fun t2vDocument() = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat", seed = 7)

    private fun completedStagesAdapter() = FakeAdapter { _, reportStage, _ ->
        reportStage(VideoRunStage.TEXT_ENCODE, null, null)
        reportStage(VideoRunStage.SAMPLE, 1, 20)
        reportStage(VideoRunStage.DECODE, null, null)
        reportStage(VideoRunStage.ENCODE, null, null)
        reportStage(VideoRunStage.PUBLISH, null, null)
        VideoExecutionOutcome.Succeeded(outputRelativePath = null)
    }

    @Test
    fun `an explicit draft seed is used verbatim on submit`() = runTest {
        val store = FakeStore()
        val owner = newOwner(store, completedStagesAdapter())
        val doc = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat", seed = 424242L)
        owner.saveRevision(doc)
        val result = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId))
        assertTrue("got $result", result is VideoSubmitResult.Admitted)
        val runId = (result as VideoSubmitResult.Admitted).runId.value
        assertEquals(
            "the run must use the seed the user typed, not a fresh random draw",
            424242L,
            store.snapshots.getValue(runId).resolvedParams.seed,
        )
    }

    @Test
    fun `a zero draft seed still randomises at submit`() = runTest {
        val store = FakeStore()
        val owner = newOwner(store, completedStagesAdapter())
        val doc = VideoWorkflowTemplates.textToVideoTemplate(prompt = "a cat", seed = 0L)
        owner.saveRevision(doc)
        val result = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId))
        assertTrue("got $result", result is VideoSubmitResult.Admitted)
        val runId = (result as VideoSubmitResult.Admitted).runId.value
        assertNotEquals(
            "seed 0 means random at submit",
            0L,
            store.snapshots.getValue(runId).resolvedParams.seed,
        )
    }

    @Test
    fun `submit without an executor is honestly rejected and nothing persists`() = runTest {
        val store = FakeStore()
        val owner = newOwner(store, adapter = null)
        owner.saveRevision(t2vDocument())
        val result = owner.submit(VideoSubmitCommand(revisionId = t2vDocument().revisionId))
        assertTrue(result is VideoSubmitResult.Rejected)
        assertEquals(VideoErrorCode.BACKEND_UNAVAILABLE, (result as VideoSubmitResult.Rejected).code)
        assertTrue("nothing may be queued without execution", store.runs.isEmpty())
    }

    @Test
    fun `happy path settles COMPLETED with released resources`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, reportStage, _ ->
            // The real pipeline walks every phase; the fake must too — the frozen transition
            // table refuses to settle COMPLETED from an early phase.
            reportStage(VideoRunStage.TEXT_ENCODE, null, null)
            reportStage(VideoRunStage.SAMPLE, 1, 20)
            reportStage(VideoRunStage.DECODE, null, null)
            reportStage(VideoRunStage.ENCODE, null, null)
            reportStage(VideoRunStage.PUBLISH, null, null)
            VideoExecutionOutcome.Succeeded(outputRelativePath = null)
        }
        val owner = newOwner(store, adapter)
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val result = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId))
        assertTrue("got $result", result is VideoSubmitResult.Admitted)
        testScheduler.runCurrent()
        adapter.started.await()
        testScheduler.runCurrent()

        val row = store.runs[(result as VideoSubmitResult.Admitted).runId.value]
        assertNotNull(row)
        assertEquals(VideoRunState.COMPLETED.name, row!!.state)
        assertEquals(VideoResourceState.RELEASED.name, row.resourceState)
    }

    @Test
    fun `remote submit requires an explicit endpoint and never falls back locally`() = runTest {
        val store = FakeStore()
        val local = FakeAdapter { _, _, _ -> VideoExecutionOutcome.Succeeded(null) }
        val remote = FakeAdapter { _, _, _ -> VideoExecutionOutcome.Succeeded(null) }
        val owner = VideoGenerationOwner(
            scope = backgroundScope,
            store = store,
            executor = local,
            remoteExecutor = remote,
            clock = { 1L },
            ids = { "v-${store.seeds.incrementAndGet()}" },
        )
        testScheduler.runCurrent()
        val doc = t2vDocument().copy(rawSourceRef = "{}")
        owner.saveRevision(doc)

        val noBinding = owner.submit(
            VideoSubmitCommand(revisionId = doc.revisionId, backend = VideoBackendKind.REMOTE_COMFY),
        )
        assertTrue(noBinding is VideoSubmitResult.Rejected)
        assertEquals(VideoErrorCode.INPUT_INVALID, (noBinding as VideoSubmitResult.Rejected).code)
        assertTrue(store.runs.isEmpty())

        val localWithBinding = owner.submit(
            VideoSubmitCommand(
                revisionId = doc.revisionId,
                remoteBinding = com.muyuchat.core.video.contract.VideoRemoteEndpointBinding(
                    endpointId = "ep-a",
                    baseUrl = "http://127.0.0.1:8188",
                ),
            ),
        )
        assertTrue(localWithBinding is VideoSubmitResult.Rejected)
        assertTrue(store.runs.isEmpty())
    }

    @Test
    fun `remote submit refuses a revision without the raw API graph`() = runTest {
        val store = FakeStore()
        val remote = FakeAdapter { _, _, _ -> VideoExecutionOutcome.Succeeded(null) }
        val owner = VideoGenerationOwner(
            scope = backgroundScope,
            store = store,
            executor = null,
            remoteExecutor = remote,
            clock = { 1L },
            ids = { "v-${store.seeds.incrementAndGet()}" },
        )
        testScheduler.runCurrent()
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val result = owner.submit(
            VideoSubmitCommand(
                revisionId = doc.revisionId,
                backend = VideoBackendKind.REMOTE_COMFY,
                remoteBinding = com.muyuchat.core.video.contract.VideoRemoteEndpointBinding(
                    endpointId = "ep-a",
                    baseUrl = "http://127.0.0.1:8188",
                ),
            ),
        )
        assertTrue(result is VideoSubmitResult.Rejected)
        assertEquals(VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE, (result as VideoSubmitResult.Rejected).code)
        assertTrue(store.runs.isEmpty())
    }

    @Test
    fun `queued run cancels directly without touching the executor`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, _, _ -> VideoExecutionOutcome.Succeeded(null) }
        val owner = newOwner(store, adapter)
        // Seed a queued row from a "previous" submit without launching (previous process style).
        store.insertRun("v-queued", "v-a1", "v-r1", compileSnapshot(store), VideoBackendKind.LOCAL_NATIVE, VideoRunState.QUEUED, 1L)
        val outcome = owner.requestCancel(VideoRunId("v-queued"))
        assertTrue(outcome.toString(), outcome is com.muyuchat.core.video.contract.VideoCancelResult.Accepted)
        assertEquals(VideoRunState.CANCELLED.name, store.runs["v-queued"]!!.state)
        assertEquals(VideoResourceState.RELEASED.name, store.runs["v-queued"]!!.resourceState)
        assertTrue("queued cancel must not reach the executor", adapter.cancelCalls.isEmpty())
    }

    @Test
    fun `cancel during sampling only settles CANCELLED after the adapter confirms stop`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, reportStage, cancelled ->
            reportStage(VideoRunStage.SAMPLE, 1, 20)
            while (!cancelled()) kotlinx.coroutines.delay(10)
            VideoExecutionOutcome.Stopped
        }
        val owner = newOwner(store, adapter)
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val admitted = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId)) as VideoSubmitResult.Admitted
        testScheduler.runCurrent()
        adapter.started.await()

        val cancel = owner.requestCancel(admitted.runId)
        assertTrue(cancel.toString(), cancel is com.muyuchat.core.video.contract.VideoCancelResult.Accepted)
        assertEquals(VideoRunState.CANCEL_REQUESTED.name, store.runs[admitted.runId.value]!!.state)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        val row = store.runs[admitted.runId.value]!!
        assertEquals("physical stop must be confirmed before CANCELLED", VideoRunState.CANCELLED.name, row.state)
        assertEquals(VideoResourceState.RELEASED.name, row.resourceState)
    }

    @Test
    fun `late adapter success after a recorded cancel still settles CANCELLED`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, _, cancelled ->
            while (!cancelled()) kotlinx.coroutines.delay(10)
            VideoExecutionOutcome.Succeeded(null) // late arrival
        }
        val owner = newOwner(store, adapter)
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val admitted = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId)) as VideoSubmitResult.Admitted
        testScheduler.runCurrent()
        adapter.started.await()
        owner.requestCancel(admitted.runId)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(VideoRunState.CANCELLED.name, store.runs[admitted.runId.value]!!.state)
    }

    @Test
    fun `adapter failure settles FAILED with its code`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, _, _ ->
            VideoExecutionOutcome.Failed(VideoErrorCode.NATIVE_EXECUTION_FAILED, "boom")
        }
        val owner = newOwner(store, adapter)
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val admitted = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId)) as VideoSubmitResult.Admitted
        testScheduler.runCurrent()
        adapter.started.await()
        testScheduler.runCurrent()
        val row = store.runs[admitted.runId.value]!!
        assertEquals(VideoRunState.FAILED.name, row.state)
        assertEquals(VideoErrorCode.NATIVE_EXECUTION_FAILED.name, row.errorCode)
    }

    @Test
    fun `stale attempt progress cannot touch the current run row`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, reportStage, _ ->
            reportStage(VideoRunStage.TEXT_ENCODE, null, null)
            reportStage(VideoRunStage.SAMPLE, null, null)
            reportStage(VideoRunStage.DECODE, null, null)
            reportStage(VideoRunStage.ENCODE, null, null)
            reportStage(VideoRunStage.PUBLISH, null, null)
            VideoExecutionOutcome.Succeeded(null)
        }
        val owner = newOwner(store, adapter)
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val first = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId)) as VideoSubmitResult.Admitted
        testScheduler.runCurrent()
        assertEquals(VideoRunState.COMPLETED.name, store.runs[first.runId.value]!!.state)

        // A stale callback with the OLD attempt id must not move the terminal row.
        store.updateProgress(first.runId.value, "v-stale-attempt", VideoRunStage.ENCODE, 1, 2, 1L)
        val row = store.runs[first.runId.value]!!
        assertEquals("stale attempt progress must not move the terminal row", VideoRunStage.PUBLISH.name, row.stage)
    }

    @Test
    fun `startup reconciliation marks previous-process runs INTERRUPTED`() = runTest {
        val store = FakeStore()
        store.insertRun("v-old", "v-a1", "v-r1", compileSnapshot(store), VideoBackendKind.LOCAL_NATIVE, VideoRunState.SAMPLING, 1L)
        newOwner(store, adapter = null)
        val row = store.runs["v-old"]!!
        assertEquals(VideoRunState.INTERRUPTED.name, row.state)
        assertEquals(VideoResourceState.UNCONFIRMED.name, row.resourceState)
        assertEquals(VideoErrorCode.INTERRUPTED_PROCESS_DEATH.name, row.errorCode)
    }

    @Test
    fun `retry creates a new run bound to the same revision`() = runTest {
        val store = FakeStore()
        val adapter = FakeAdapter { _, reportStage, _ ->
            reportStage(VideoRunStage.TEXT_ENCODE, null, null)
            reportStage(VideoRunStage.SAMPLE, null, null)
            reportStage(VideoRunStage.DECODE, null, null)
            reportStage(VideoRunStage.ENCODE, null, null)
            reportStage(VideoRunStage.PUBLISH, null, null)
            VideoExecutionOutcome.Succeeded(null)
        }
        val owner = newOwner(store, adapter)
        val doc = t2vDocument()
        owner.saveRevision(doc)
        val first = owner.submit(VideoSubmitCommand(revisionId = doc.revisionId)) as VideoSubmitResult.Admitted
        testScheduler.runCurrent()

        val retried = owner.retry(first.runId)
        assertTrue("retry must admit a new run, got $retried", retried is VideoSubmitResult.Admitted)
        val second = retried as VideoSubmitResult.Admitted
        assertNotEquals(first.runId, second.runId)
        assertNotEquals(first.attemptId, second.attemptId)
        assertEquals(store.runs[first.runId.value]!!.revisionId, store.runs[second.runId.value]!!.revisionId)
    }

    @Test
    fun `retry of a non-terminal run is rejected`() = runTest {
        val store = FakeStore()
        val owner = newOwner(store, adapter = null)
        // Seed a LIVE row after startup reconciliation so it stays SAMPLING.
        store.insertRun("v-live", "v-a1", "v-r1", compileSnapshot(store), VideoBackendKind.LOCAL_NATIVE, VideoRunState.SAMPLING, 1L)
        val result = owner.retry(VideoRunId("v-live"))
        assertTrue(result.toString(), result is VideoSubmitResult.Rejected)
        assertEquals(VideoErrorCode.INPUT_INVALID, (result as VideoSubmitResult.Rejected).code)
    }

    @Test
    fun `saveRevision is idempotent and conflicts on changed content`() = runTest {
        val store = FakeStore()
        val owner = newOwner(store, adapter = null)
        val doc = t2vDocument()
        val saved = owner.saveRevision(doc)
        assertTrue(saved is com.muyuchat.core.video.contract.VideoRevisionResult.Saved)
        val again = owner.saveRevision(doc)
        assertTrue(again is com.muyuchat.core.video.contract.VideoRevisionResult.Saved)
        val changed = doc.copy(
            nodes = doc.nodes.map {
                if (it.nodeId == "n_input") it.copy(params = it.params + ("prompt" to com.muyuchat.core.video.contract.VideoNodeValue.TextValue("a dog"))) else it
            },
        )
        val conflict = owner.saveRevision(changed)
        assertTrue(conflict is com.muyuchat.core.video.contract.VideoRevisionResult.Conflict)
    }

    /** Compiles a real snapshot via the production compiler for row seeding. */
    private fun compileSnapshot(store: FakeStore): com.muyuchat.core.video.contract.VideoRunSnapshot {
        val doc = t2vDocument()
        val compiled = com.muyuchat.mca.video.workflow.VideoWorkflowCompiler.compile(
            doc, Wan22Ti2v5bProfiles.Q4_0_Q8_0,
            com.muyuchat.mca.video.workflow.VideoWorkflowCompiler.SeedPolicy.Fixed(11L),
        )
        return (compiled as com.muyuchat.mca.video.workflow.VideoWorkflowCompiler.Result.Compiled).snapshot
    }
}

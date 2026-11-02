package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentStepKind
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.contract.UpsertArtifactTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStartupRecoveryTest {

    @Test
    fun queuedRunPausesInterrupted() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.QUEUED))
        val report = recover(journal)
        val result = report.results.single() as AgentRunRecoveryResult.Recovered
        assertEquals(AgentRunState.PAUSED, result.toState)
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
        assertEquals(AgentRunState.PAUSED, journal.run().state)
    }

    @Test
    fun waitingResourceRunPausesInterrupted() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.WAITING_RESOURCE))
        val result = recover(journal).results.single() as AgentRunRecoveryResult.Recovered
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
        assertEquals(AgentRunState.PAUSED, journal.run().state)
    }

    @Test
    fun waitingUserRunPausesInterrupted() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.WAITING_USER))
        val result = recover(journal).results.single() as AgentRunRecoveryResult.Recovered
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
    }

    @Test
    fun runningModelGenerationPausesInterrupted() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                steps = listOf(step(kind = AgentStepKind.MODEL, status = AgentStepStatus.RUNNING))
            )
        )
        val result = recover(journal).results.single() as AgentRunRecoveryResult.Recovered
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
    }

    @Test
    fun approvedDispatchIntentNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                steps = listOf(step(kind = AgentStepKind.TOOL, status = AgentStepStatus.WAITING))
            )
        )
        val records = FakeRecords(
            invocations = mapOf("invocation-1" to invocation(ToolInvocationStatus.APPROVED))
        )

        val result = recover(journal, records).results.single() as AgentRunRecoveryResult.NeedsReview

        assertTrue(result.reasonRef.contains("external_side_effect_unknown"))
        assertEquals(AgentRunState.NEEDS_REVIEW, journal.run().state)
    }

    @Test
    fun runningToolStepNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                steps = listOf(step(kind = AgentStepKind.TOOL, status = AgentStepStatus.RUNNING))
            )
        )
        val result = recover(journal).results.single() as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.contains("external_side_effect_unknown"))
        assertEquals(AgentRunState.NEEDS_REVIEW, journal.run().state)
    }

    @Test
    fun waitingApprovalExpiresApprovalAndInvocationThenPauses() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.WAITING_APPROVAL,
                pendingApproval = approval(),
                steps = listOf(step(kind = AgentStepKind.APPROVAL, status = AgentStepStatus.WAITING))
            )
        )
        val records = FakeRecords(
            invocations = mapOf("invocation-1" to invocation(ToolInvocationStatus.WAITING_APPROVAL))
        )
        val result = recover(journal, records = records).results.single() as AgentRunRecoveryResult.Recovered
        assertEquals(AgentPauseReason.APPROVAL_EXPIRED, result.pauseReason)
        assertEquals(AgentApprovalStatus.EXPIRED, journal.snapshot().pendingApproval!!.status)
        assertEquals(ToolInvocationStatus.NOT_EXECUTED, journal.invocations["invocation-1"]!!.status)
        assertEquals(AgentRunState.PAUSED, journal.run().state)
        assertEquals(3, journal.committedCommands.size)
    }

    @Test
    fun waitingApprovalWithoutInvocationNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(state = AgentRunState.WAITING_APPROVAL, pendingApproval = approval())
        )
        val result = recover(journal, records = FakeRecords()).results.single()
            as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.contains("approval_invocation_missing"))
        assertEquals(AgentRunState.NEEDS_REVIEW, journal.run().state)
        assertEquals(AgentApprovalStatus.EXPIRED, journal.snapshot().pendingApproval!!.status)
    }

    @Test
    fun pausingWithQuiescentStopProofPausesInterrupted() = runBlocking {
        val journal = FakeJournal(
            snapshot(state = AgentRunState.PAUSING, steps = listOf(step(kind = AgentStepKind.MODEL, status = AgentStepStatus.RUNNING)))
        )
        val records = FakeRecords(attempts = mapOf("run-1" to attempt()))
        val proof = FakeStopProof(AgentQuiescenceEvidence.Quiescent("engine_token_released"))
        val result = recover(journal, records = records, stopProof = proof).results.single()
            as AgentRunRecoveryResult.Recovered
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
        assertEquals("agent:run-1:0:1", proof.lastRef!!.backendRequestId)
        assertEquals(AgentRunState.PAUSED, journal.run().state)
    }

    @Test
    fun stoppingWithoutQuiescentProofNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(state = AgentRunState.STOPPING, steps = listOf(step(kind = AgentStepKind.MODEL, status = AgentStepStatus.RUNNING)))
        )
        val records = FakeRecords(attempts = mapOf("run-1" to attempt()))
        val proof = FakeStopProof(AgentQuiescenceEvidence.NotQuiescent("runner_active"))
        val result = recover(journal, records = records, stopProof = proof).results.single()
            as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.startsWith("stop_not_confirmed"))
        assertEquals(AgentRunState.NEEDS_REVIEW, journal.run().state)
    }

    @Test
    fun stoppingWithoutAttemptNeedsReview() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.STOPPING))
        val result = recover(journal).results.single() as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.contains("stop_proof_reference_missing"))
    }

    @Test
    fun preparingArtifactConfirmedCommittedIsRecordedAndRunPauses() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                artifacts = listOf(artifact(AgentArtifactCommitState.PREPARING))
            )
        )
        val records = FakeRecords(commits = mapOf("artifact-1" to artifactCommit(AgentArtifactCommitPhase.RENAMED)))
        val reconciler = FakeArtifactReconciler(AgentArtifactReconcileResult.Committed("fsync_ok"))
        val result = recover(journal, records = records, reconciler = reconciler).results.single()
            as AgentRunRecoveryResult.Recovered
        assertEquals(AgentArtifactCommitState.COMMITTED, journal.artifact("artifact-1").commitState)
        assertEquals(AgentArtifactCommitPhase.COMMITTED, journal.artifactCommits["artifact-1"]!!.phase)
        assertEquals(AgentRunState.PAUSED, journal.run().state)
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
    }

    @Test
    fun preparingArtifactUnknownNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                artifacts = listOf(artifact(AgentArtifactCommitState.PREPARING))
            )
        )
        val records = FakeRecords(commits = mapOf("artifact-1" to artifactCommit(AgentArtifactCommitPhase.RENAMED)))
        val reconciler = FakeArtifactReconciler(AgentArtifactReconcileResult.Unknown)
        val result = recover(journal, records = records, reconciler = reconciler).results.single()
            as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.contains("artifact_commit_unknown"))
        assertEquals(AgentRunState.NEEDS_REVIEW, journal.run().state)
        assertEquals(AgentArtifactCommitState.PREPARING, journal.artifact("artifact-1").commitState)
    }

    @Test
    fun renamedArtifactNotCommittedNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                artifacts = listOf(artifact(AgentArtifactCommitState.PREPARING))
            )
        )
        val records = FakeRecords(commits = mapOf("artifact-1" to artifactCommit(AgentArtifactCommitPhase.RENAMED)))
        val reconciler = FakeArtifactReconciler(AgentArtifactReconcileResult.NotCommitted)
        val result = recover(journal, records = records, reconciler = reconciler).results.single()
            as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.contains("artifact_commit_not_committed"))
    }

    @Test
    fun preparingArtifactMissingCommitNeedsReview() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                artifacts = listOf(artifact(AgentArtifactCommitState.PREPARING))
            )
        )
        val result = recover(journal).results.single() as AgentRunRecoveryResult.NeedsReview
        assertTrue(result.reasonRef.contains("artifact_commit_missing"))
    }

    @Test
    fun committedArtifactDoesNotBlockRecovery() = runBlocking {
        val journal = FakeJournal(
            snapshot(
                state = AgentRunState.RUNNING,
                artifacts = listOf(artifact(AgentArtifactCommitState.COMMITTED))
            )
        )
        val result = recover(journal).results.single() as AgentRunRecoveryResult.Recovered
        assertEquals(AgentRunState.PAUSED, journal.run().state)
        assertEquals(AgentPauseReason.INTERRUPTED, result.pauseReason)
    }

    @Test
    fun terminalRunIsNoOp() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.SUCCEEDED), includeTerminalInList = true)
        val result = recover(journal).results.single() as AgentRunRecoveryResult.NoOp
        assertEquals(AgentRecoveryNoOpReason.TERMINAL, result.reason)
        assertEquals(0, journal.committedCommands.size)
    }

    @Test
    fun alreadyPausedRunIsNoOp() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.PAUSED))
        val result = recover(journal).results.single() as AgentRunRecoveryResult.NoOp
        assertEquals(AgentRecoveryNoOpReason.ALREADY_SETTLED, result.reason)
        assertEquals(0, journal.committedCommands.size)
    }

    @Test
    fun secondReconcileIsIdempotent() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.QUEUED))
        recover(journal)
        val commandsAfterFirst = journal.committedCommands.size
        val second = recover(journal)
        val result = second.results.single() as AgentRunRecoveryResult.NoOp
        assertEquals(AgentRecoveryNoOpReason.ALREADY_SETTLED, result.reason)
        assertEquals(commandsAfterFirst, journal.committedCommands.size)
    }

    @Test
    fun oneRunFailureDoesNotAbortOtherRuns() = runBlocking {
        val journal = FakeJournal(
            snapshot(state = AgentRunState.QUEUED, runId = "run-1"),
            snapshot(state = AgentRunState.QUEUED, runId = "run-2")
        )
        journal.failCommitForRunId = "run-1"
        val report = recover(journal)
        assertEquals(2, report.scanned)
        assertTrue(report.results[0] is AgentRunRecoveryResult.Failed)
        assertTrue(report.results[1] is AgentRunRecoveryResult.Recovered)
        assertEquals(AgentRunState.PAUSED, journal.runs["run-2"]!!.run.state)
    }

    @Test
    fun scanFailureIsReportedWithoutThrowing() = runBlocking {
        val journal = FakeJournal(snapshot(state = AgentRunState.QUEUED))
        journal.failScan = true
        val report = recover(journal)
        assertEquals(0, report.scanned)
        assertTrue(report.scanError != null)
    }

    private suspend fun recover(
        journal: FakeJournal,
        records: AgentRecoveryRecordSource = AgentRecoveryRecordSource.Unavailable,
        reconciler: AgentArtifactReconciler = AgentArtifactReconciler.Never,
        stopProof: AgentStopProofSource = AgentStopProofSource.Unavailable
    ): AgentStartupRecoveryReport = AgentStartupRecovery(
        journal = journal,
        records = records,
        artifactReconciler = reconciler,
        stopProofSource = stopProof,
        clock = { NOW_MILLIS }
    ).reconcile()

    private fun snapshot(
        state: AgentRunState,
        runId: String = "run-1",
        steps: List<AgentStep> = emptyList(),
        artifacts: List<AgentArtifact> = emptyList(),
        pendingApproval: AgentApproval? = null
    ): AgentRunSnapshot = AgentRunSnapshot(
        run = run(state = state, runId = runId),
        task = AgentTask(
            taskId = "task-1",
            workspaceId = "workspace-1",
            goal = "private goal that recovery must never copy",
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
            displayStatus = AgentDisplayStatus.QUEUED,
            lastRunId = runId,
            revision = 0L
        ),
        pendingApproval = pendingApproval,
        steps = steps,
        artifacts = artifacts
    )

    private fun run(state: AgentRunState, runId: String = "run-1"): AgentRun = AgentRun(
        runId = runId,
        taskId = "task-1",
        runOrdinal = 1L,
        state = state,
        reason = AgentPauseReason.NONE,
        epoch = 7L,
        modelBinding = AgentModelBinding(
            providerId = "local",
            modelId = "mnn-qwen",
            runtime = "MNN_CPU",
            assetFingerprint = "asset-fingerprint",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            modelDisplayName = "Qwen"
        ),
        policy = AgentRunPolicy(),
        budget = AgentBudget(),
        spentBudget = AgentSpentBudget(),
        lastSequence = 0L,
        revision = 0L,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )

    private fun step(
        kind: AgentStepKind,
        status: AgentStepStatus,
        invocationId: String? = "invocation-1"
    ): AgentStep = AgentStep(
        stepId = "step-1",
        runId = "run-1",
        index = 0,
        kind = kind,
        status = status,
        modelAttemptId = null,
        invocationId = invocationId,
        startedAtMillis = 1_000L,
        endedAtMillis = null
    )

    private fun approval(): AgentApproval = AgentApproval(
        approvalId = "approval-1",
        invocationId = "invocation-1",
        principal = "local-user",
        argumentsHash = "arguments-hash",
        toolSchemaHash = "schema-hash",
        resourceFingerprint = "resource-1",
        policyVersion = 1L,
        runEpoch = 7L,
        expiresAtMillis = 5_000L,
        status = AgentApprovalStatus.PENDING,
        createdAtMillis = 1_000L
    )

    private fun invocation(status: ToolInvocationStatus): ToolInvocation = ToolInvocation(
        invocationId = "invocation-1",
        runId = "run-1",
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = "file_write",
        toolVersion = "1",
        schemaHash = "schema-hash",
        frozenArgumentsRef = null,
        argumentsHash = "arguments-hash",
        resourceFingerprint = "resource-1",
        status = status,
        idempotencyKey = "idem-1",
        resultRef = null,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )

    private fun attempt(): AgentModelAttempt = AgentModelAttempt(
        attemptId = "attempt-1",
        runId = "run-1",
        stepIndex = 0,
        ordinal = 1,
        backendRequestId = "agent:run-1:0:1",
        backendIdentityRef = null,
        bindingFingerprint = "binding-fingerprint",
        startedAtMillis = 1_000L,
        terminal = com.muyuchat.core.agent.contract.AgentAttemptTerminal.RUNNING
    )

    private fun artifact(state: AgentArtifactCommitState): AgentArtifact = AgentArtifact(
        artifactId = "artifact-1",
        taskId = "task-1",
        invocationId = "invocation-1",
        relativePath = "out/report.md",
        mediaType = "text/markdown",
        sizeBytes = 12L,
        sha256 = "a".repeat(64),
        commitState = state,
        createdAtMillis = 1_000L
    )

    private fun artifactCommit(phase: AgentArtifactCommitPhase): AgentArtifactCommit = AgentArtifactCommit(
        commitId = "commit-1",
        invocationId = "invocation-1",
        artifactId = "artifact-1",
        stagingRef = "staging-1",
        targetLogicalPath = "out/report.md",
        expectedOldHash = null,
        newHash = "a".repeat(64),
        newSizeBytes = 12L,
        phase = phase,
        updatedAtMillis = 1_000L
    )

    private class FakeRecords(
        val invocations: Map<String, ToolInvocation> = emptyMap(),
        val commits: Map<String, AgentArtifactCommit> = emptyMap(),
        val attempts: Map<String, AgentModelAttempt> = emptyMap()
    ) : AgentRecoveryRecordSource {
        override suspend fun findInvocation(invocationId: String): ToolInvocation? = invocations[invocationId]

        override suspend fun findArtifactCommit(artifactId: String): AgentArtifactCommit? = commits[artifactId]

        override suspend fun findLatestAttempt(runId: String): AgentModelAttempt? = attempts[runId]
    }

    private class FakeArtifactReconciler(
        private val result: AgentArtifactReconcileResult
    ) : AgentArtifactReconciler {
        override suspend fun confirmCommitted(commit: AgentArtifactCommit): AgentArtifactReconcileResult = result
    }

    private class FakeStopProof(
        private val evidence: AgentQuiescenceEvidence
    ) : AgentStopProofSource {
        var lastRef: AgentExecutionRef? = null

        override suspend fun read(ref: AgentExecutionRef): AgentQuiescenceEvidence = evidence

        override suspend fun awaitQuiescent(
            ref: AgentExecutionRef,
            timeoutMillis: Long
        ): AgentQuiescenceEvidence {
            lastRef = ref
            return evidence
        }
    }

    private class FakeJournal(
        vararg initial: AgentRunSnapshot,
        private val includeTerminalInList: Boolean = false
    ) : AgentJournal {
        val runs: MutableMap<String, AgentRunSnapshot> =
            initial.associateBy { it.run.runId }.toMutableMap()
        val invocations: MutableMap<String, ToolInvocation> = mutableMapOf()
        val artifactCommits: MutableMap<String, AgentArtifactCommit> = mutableMapOf()
        val committedCommands: MutableList<AgentTransition> = mutableListOf()
        private val commandResults: MutableMap<String, AgentCommitResult> = mutableMapOf()
        var failCommitForRunId: String? = null
        var failScan: Boolean = false

        fun run(): AgentRun = snapshot().run

        fun snapshot(): AgentRunSnapshot = runs.getValue("run-1")

        fun artifact(artifactId: String): AgentArtifact =
            snapshot().artifacts.first { it.artifactId == artifactId }

        override suspend fun readRun(runId: String): AgentRunSnapshot? = runs[runId]?.copy()

        override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> {
            if (failScan) throw IllegalStateException("fake scan failure")
            return runs.values
                .filter { includeTerminalInList || !it.run.state.isTerminal }
                .take(limit)
                .map { it.copy() }
        }

        override suspend fun readEvents(
            runId: String,
            afterSequence: Long,
            limit: Int
        ): AgentEventPage = AgentEventPage(events = emptyList())

        override suspend fun commit(command: AgentTransition): AgentCommitResult {
            commandResults[command.commandId]?.let { return it.copy(idempotentReplay = true) }
            val failingRunId = failCommitForRunId
            if (failingRunId != null && command.runId == failingRunId) {
                throw IllegalStateException("fake commit failure")
            }
            val result = when (command) {
                is UpdateRunTransition -> {
                    val current = runs[command.runId]
                        ?: throw IllegalStateException("unknown run ${command.runId}")
                    check(command.expectedRevision == current.run.revision) {
                        "revision mismatch: expected ${command.expectedRevision}, actual ${current.run.revision}"
                    }
                    check(command.run.revision == current.run.revision + 1L) {
                        "updated run revision must be current + 1"
                    }
                    runs[command.runId] = current.copy(
                        run = command.run,
                        task = current.task.copy(
                            displayStatus = command.taskDisplay,
                            revision = current.task.revision + 1L,
                            updatedAtMillis = command.run.updatedAtMillis
                        )
                    )
                    AgentCommitResult(
                        appliedRevision = command.run.revision,
                        taskRevision = current.task.revision + 1L,
                        runRevision = command.run.revision
                    )
                }

                is UpsertApprovalTransition -> {
                    val current = runs.values.firstOrNull {
                        it.pendingApproval?.approvalId == command.approval.approvalId
                    } ?: throw IllegalStateException("unknown approval")
                    runs[current.run.runId] = current.copy(pendingApproval = command.approval)
                    AgentCommitResult(appliedRevision = current.run.revision)
                }

                is UpsertInvocationTransition -> {
                    invocations[command.invocation.invocationId] = command.invocation
                    AgentCommitResult(appliedRevision = 0L)
                }

                is UpsertArtifactTransition -> {
                    val owner = runs.values.firstOrNull { snapshot ->
                        snapshot.artifacts.any { it.artifactId == command.artifact.artifactId }
                    } ?: throw IllegalStateException("unknown artifact")
                    runs[owner.run.runId] = owner.copy(
                        artifacts = owner.artifacts.map {
                            if (it.artifactId == command.artifact.artifactId) command.artifact else it
                        }
                    )
                    command.commit?.let { artifactCommits[it.artifactId] = it }
                    AgentCommitResult(appliedRevision = owner.run.revision)
                }

                else -> throw IllegalStateException("unexpected transition ${command.javaClass.simpleName}")
            }
            committedCommands += command
            commandResults[command.commandId] = result
            return result
        }
    }

    private companion object {
        const val NOW_MILLIS = 10_000L
    }
}

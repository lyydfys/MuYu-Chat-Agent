package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationRecord
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.journal.AgentToolProposal
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.journal.InMemoryAgentJournal
import com.muyuchat.core.agent.runtime.run.AgentActiveAttemptRegistry
import com.muyuchat.core.agent.runtime.run.AgentCommandProcessor
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.runtime.run.AgentMonotonicClock
import com.muyuchat.core.agent.runtime.run.AgentResumeToolDecision
import com.muyuchat.core.agent.runtime.run.AgentRunExecutor
import com.muyuchat.core.agent.runtime.run.AgentRunOwner
import com.muyuchat.core.agent.runtime.run.AgentRunSpec
import com.muyuchat.core.agent.runtime.run.AgentRunSpecProvider
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationPort
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationRequest
import com.muyuchat.core.agent.runtime.run.AgentToolAuthorizationResult
import com.muyuchat.core.agent.runtime.run.AgentToolDenialResult
import com.muyuchat.core.agent.runtime.run.AuthorizedToolCommand
import com.muyuchat.core.agent.runtime.run.SequentialAgentIdGenerator
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.platform.AgentArtifactReconciler
import com.muyuchat.mca.agent.platform.AgentRecoveryRecordSource
import com.muyuchat.mca.agent.platform.AgentRunRecoveryResult
import com.muyuchat.mca.agent.platform.AgentStartupRecovery
import com.muyuchat.mca.agent.security.AgentApprovalBinding
import com.muyuchat.mca.agent.security.AgentApprovalConsumeOutcome
import com.muyuchat.mca.agent.security.AgentApprovalResolutionResult
import com.muyuchat.mca.agent.security.AgentFreezeRequest
import com.muyuchat.mca.agent.security.AgentFreezeResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import com.muyuchat.mca.agent.security.AgentPermissionBroker
import com.muyuchat.mca.agent.security.AgentPermissionCandidate
import com.muyuchat.mca.agent.security.AgentPermissionDecision
import com.muyuchat.mca.agent.security.AgentPermissionPolicy
import com.muyuchat.mca.agent.security.AgentWorkspaceReadGrant
import com.muyuchat.mca.agent.security.InMemoryAgentApprovalStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-A end-to-end closure at the app boundary: the real broker, the real PENDING -> CONSUMED CAS
 * (in-memory adapter), the real owner and the broker-backed authorization adapter.
 *
 * It proves: exactly one authorization per approval; deny and cancelled/terminal runs never
 * authorize; a changed epoch/policy fails closed; restart recovery settles the invocation as
 * NOT_EXECUTED and expires the approval; and an owner double-click executes at most once.
 */
class AgentToolApprovalClosureTest {

    private val validator = SupportedToolSchemaValidator()
    private val factory = AgentFrozenCommandFactory()
    private val descriptor = AgentV1ToolSpecs.descriptor(
        AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT,
        validator
    )

    @Test
    fun pendingApprovalIsConsumedExactlyOnce() = runTest {
        val seed = seedJournal()
        val frozen = freezeFor(seed)
        seed.seedInvocation(frozen, descriptor, markWaiting = true)
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val approval = approve(broker, seed, frozen)
        val authorization = BrokerAgentToolAuthorization(seed.journal, broker)

        val first = authorization.authorize(seed.request(frozen, approval.approvalId))
        assertTrue("expected exactly one authorization, got $first", first is AgentToolAuthorizationResult.Authorized)
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approval.approvalId))

        val replay = authorization.authorize(seed.request(frozen, approval.approvalId))
        assertTrue(replay is AgentToolAuthorizationResult.NotExecuted)
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approval.approvalId))
    }

    @Test
    fun consumePendingRequiresStatusBindingEpochPolicyAndExpiry() = runTest {
        val seed = seedJournal()
        val frozen = freezeFor(seed)
        seed.seedInvocation(frozen, descriptor, markWaiting = true)
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val approval = approve(broker, seed, frozen)
        val command = frozen.command
        val binding = AgentApprovalBinding.of(command)
        val policyVersion = seed.run.policy.policyVersion

        val changedArguments = store.consumePending(
            approval.approvalId,
            binding.copy(argumentsHash = "0".repeat(64)),
            seed.run.epoch,
            policyVersion,
            NOW
        )
        assertTrue(changedArguments is AgentApprovalConsumeOutcome.BindingMismatch)

        val changedEpoch = store.consumePending(
            approval.approvalId,
            binding,
            seed.run.epoch + 1L,
            policyVersion,
            NOW
        )
        assertTrue(changedEpoch is AgentApprovalConsumeOutcome.BindingMismatch)

        val changedPolicy = store.consumePending(
            approval.approvalId,
            binding,
            seed.run.epoch,
            policyVersion + 1L,
            NOW
        )
        assertTrue(changedPolicy is AgentApprovalConsumeOutcome.BindingMismatch)
        assertEquals(AgentApprovalStatus.PENDING, store.statusOf(approval.approvalId))

        val expired = store.consumePending(
            approval.approvalId,
            binding,
            seed.run.epoch,
            policyVersion,
            NOW + 300_001L
        )
        assertTrue(expired is AgentApprovalConsumeOutcome.Expired)
        assertEquals(AgentApprovalStatus.EXPIRED, store.statusOf(approval.approvalId))

        val afterExpiry = store.consumePending(
            approval.approvalId,
            binding,
            seed.run.epoch,
            policyVersion,
            NOW
        )
        assertFalse(afterExpiry is AgentApprovalConsumeOutcome.Consumed)

        val secondApproval = approve(broker, seed, frozen)
        val consumed = store.consumePending(
            secondApproval.approvalId,
            binding,
            seed.run.epoch,
            policyVersion,
            NOW
        )
        assertTrue(consumed is AgentApprovalConsumeOutcome.Consumed)
        val again = store.consumePending(
            secondApproval.approvalId,
            binding,
            seed.run.epoch,
            policyVersion,
            NOW
        )
        assertTrue(again is AgentApprovalConsumeOutcome.AlreadyConsumed)
    }

    @Test
    fun changedRunEpochNeverAuthorizesAndBurnsTheApproval() = runTest {
        val seed = seedJournal()
        val frozen = freezeFor(seed)
        seed.seedInvocation(frozen, descriptor, markWaiting = true)
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val approval = approve(broker, seed, frozen)
        val authorization = BrokerAgentToolAuthorization(seed.journal, broker)

        val result = authorization.authorize(
            seed.request(frozen, approval.approvalId).copy(
                run = seed.run.copy(epoch = seed.run.epoch + 1L)
            )
        )
        assertTrue(result is AgentToolAuthorizationResult.NotExecuted)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approval.approvalId))
    }

    @Test
    fun denyIsRecordedAndNeverAuthorizes() = runTest {
        val seed = seedJournal()
        val frozen = freezeFor(seed)
        seed.seedInvocation(frozen, descriptor, markWaiting = true)
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val approval = approve(broker, seed, frozen)
        val authorization = BrokerAgentToolAuthorization(seed.journal, broker)

        val denied = authorization.deny(seed.request(frozen, approval.approvalId))
        assertTrue(denied is AgentToolDenialResult.Denied)
        assertEquals(AgentApprovalStatus.DENIED, store.statusOf(approval.approvalId))

        val authorizeAfterDeny = authorization.authorize(seed.request(frozen, approval.approvalId))
        assertTrue(authorizeAfterDeny is AgentToolAuthorizationResult.NotExecuted)
        assertEquals(AgentApprovalStatus.DENIED, store.statusOf(approval.approvalId))
        assertEquals(
            ToolInvocationStatus.WAITING_APPROVAL,
            seed.journal.readInvocation(frozen.command.invocationId)!!.status
        )
    }

    @Test
    fun cancelledOrTerminalRunNeverConsumesAnApproval() = runTest {
        val seed = seedJournal()
        val frozen = freezeFor(seed)
        seed.seedInvocation(frozen, descriptor, markWaiting = true)
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val approval = approve(broker, seed, frozen)
        val authorization = BrokerAgentToolAuthorization(seed.journal, broker)

        val result = authorization.authorize(
            seed.request(frozen, approval.approvalId).copy(
                run = seed.run.copy(state = AgentRunState.CANCELLED)
            )
        )
        assertTrue(result is AgentToolAuthorizationResult.NotExecuted)
        assertEquals(AgentApprovalStatus.INVALIDATED, store.statusOf(approval.approvalId))
    }

    @Test
    fun restartRecoverySettlesInvocationNotExecutedAndExpiresApproval() = runTest {
        val seed = seedJournal()
        val frozen = freezeFor(seed)
        seed.seedInvocation(frozen, descriptor, markWaiting = true)
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val approval = approve(broker, seed, frozen)
        val waitingRun = seed.run.copy(
            state = AgentRunState.WAITING_APPROVAL,
            revision = seed.run.revision + 1L,
            updatedAtMillis = NOW
        )
        seed.journal.commit(
            UpdateRunTransition(
                commandId = "waiting",
                run = waitingRun,
                taskDisplay = AgentDisplayStatus.WAITING_APPROVAL,
                expectedRevision = seed.run.revision
            )
        )

        val recovery = AgentStartupRecovery(
            journal = seed.journal,
            records = object : AgentRecoveryRecordSource {
                override suspend fun findInvocation(invocationId: String): ToolInvocation? =
                    seed.journal.readInvocation(invocationId)

                override suspend fun findArtifactCommit(artifactId: String): AgentArtifactCommit? = null

                override suspend fun findLatestAttempt(runId: String): AgentModelAttempt? = null
            },
            artifactReconciler = AgentArtifactReconciler.Never,
            clock = { NOW }
        )
        val report = recovery.reconcile()

        val outcome = report.results.single()
        assertTrue("expected recovery, got $outcome", outcome is AgentRunRecoveryResult.Recovered)
        outcome as AgentRunRecoveryResult.Recovered
        assertEquals(AgentRunState.PAUSED, outcome.toState)
        assertEquals(AgentPauseReason.APPROVAL_EXPIRED, outcome.pauseReason)
        assertEquals(
            ToolInvocationStatus.NOT_EXECUTED,
            seed.journal.readInvocation(frozen.command.invocationId)!!.status
        )
        assertTrue(
            store.statusOf(approval.approvalId) == AgentApprovalStatus.EXPIRED ||
                store.statusOf(approval.approvalId) == AgentApprovalStatus.PENDING ||
                store.statusOf(approval.approvalId) == AgentApprovalStatus.INVALIDATED
        )
        val paused = seed.journal.readRun(seed.run.runId)!!.run
        assertEquals(AgentRunState.PAUSED, paused.state)

        // No approval can be reused after the restart settlement. Reconciliation expires the
        // approval, and the broker then invalidates it as part of the NotExecuted settlement, so
        // the post-restart state is terminal-but-unusable; PENDING would be the only failure.
        val authorization = BrokerAgentToolAuthorization(seed.journal, broker)
        val reuse = authorization.authorize(
            seed.request(frozen, approval.approvalId).copy(run = paused)
        )
        assertTrue(reuse is AgentToolAuthorizationResult.NotExecuted)
        assertTrue(
            "a restart-settled approval must be terminal and unusable, was ${store.statusOf(approval.approvalId)}",
            store.statusOf(approval.approvalId) != AgentApprovalStatus.PENDING
        )
    }

    @Test
    fun ownerDoubleClickExecutesAtMostOnceWithTheRealBroker() = runTest {
        val journal = InMemoryAgentJournal()
        val store = InMemoryAgentApprovalStore()
        val broker = AgentPermissionBroker(store)
        val authorization = BrokerAgentToolAuthorization(journal, broker)
        val executor = ApprovalResumeExecutor(journal, broker, factory, descriptor, validator)
        val clock = AgentMonotonicClock { NOW }
        val ids = SequentialAgentIdGenerator("closure-owner")
        val owner = AgentRunOwner(
            journal = journal,
            ledger = journal,
            taskReader = journal,
            questionReader = journal,
            approvalReader = journal,
            toolLedger = journal,
            toolAuthorization = authorization,
            specProvider = AgentRunSpecProvider {
                AgentRunSpec(
                    modelBinding = binding(),
                    policy = AgentRunPolicy(),
                    budget = AgentBudget()
                )
            },
            executor = executor,
            processor = AgentCommandProcessor(backgroundScope),
            clock = clock,
            ids = ids,
            scope = backgroundScope,
            attemptRegistry = AgentActiveAttemptRegistry()
        )
        val created = owner.createTask(
            CreateAgentTask(
                commandId = ids.nextId("cmd"),
                goal = "write a report",
                displayTitle = "closure",
                modelBinding = binding(),
                policy = AgentRunPolicy(),
                budget = AgentBudget()
            )
        )
        assertTrue(created is AgentCommandResult.Accepted)
        val task = journal.allTasks().single()
        val started = owner.startRun(
            StartAgentRun(ids.nextId("cmd"), task.taskId, task.revision)
        )
        assertTrue(started is AgentCommandResult.Accepted)
        val runId = (started as AgentCommandResult.Accepted).runId!!
        runCurrent()

        val waiting = journal.readRun(runId)!!.run
        assertEquals(AgentRunState.WAITING_APPROVAL, waiting.state)
        val approval = requireNotNull(executor.approval)
        assertEquals(AgentApprovalStatus.PENDING, store.statusOf(approval.approvalId))
        assertEquals(1, executor.executeCount)

        val allow = ResolveAgentApproval(
            commandId = ids.nextId("cmd"),
            runId = runId,
            expectedRunRevision = waiting.revision,
            approvalId = approval.approvalId,
            decision = AgentApprovalDecision.ALLOW_ONCE
        )
        assertTrue(owner.resolveApproval(allow) is AgentCommandResult.Accepted)
        runCurrent()
        assertEquals(2, executor.executeCount)
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approval.approvalId))
        assertEquals(AgentRunState.SUCCEEDED, journal.readRun(runId)!!.run.state)
        val resumed = requireNotNull(executor.resumes[1])
        assertTrue(resumed.toolDecision is AgentResumeToolDecision.Authorized)
        assertEquals(
            approval.invocationId,
            (resumed.toolDecision as AgentResumeToolDecision.Authorized)
                .authorization.command.invocationId
        )

        val secondClick = owner.resolveApproval(
            allow.copy(
                commandId = ids.nextId("cmd"),
                expectedRunRevision = journal.readRun(runId)!!.run.revision
            )
        )
        assertTrue(secondClick is AgentCommandResult.Rejected)
        assertEquals(2, executor.executeCount)
        assertEquals(AgentApprovalStatus.CONSUMED, store.statusOf(approval.approvalId))
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------------------

    private data class Seed(
        val journal: InMemoryAgentJournal,
        val task: AgentTask,
        val run: AgentRun
    ) {
        fun request(
            frozen: AgentFreezeResult.Frozen,
            approvalId: String
        ): AgentToolAuthorizationRequest = AgentToolAuthorizationRequest(
            run = run.copy(state = AgentRunState.WAITING_APPROVAL),
            task = task,
            invocationId = frozen.command.invocationId,
            approvalId = approvalId,
            nowMillis = NOW
        )

        suspend fun seedInvocation(
            frozen: AgentFreezeResult.Frozen,
            descriptor: AgentToolDescriptor,
            markWaiting: Boolean
        ) {
            val command = frozen.command
            val proposal = AgentToolProposal(
                invocationId = command.invocationId,
                runId = run.runId,
                taskId = task.taskId,
                stepId = command.stepId,
                stepIndex = 1,
                callOrdinal = command.callOrdinal,
                toolKey = command.toolKey,
                toolVersion = command.toolVersion,
                schemaHash = command.schemaHash,
                argumentsHash = "b".repeat(64),
                proposedAtMillis = NOW
            )
            check(journal.recordProposed(proposal) is AgentToolInvocationRecord.Recorded)
            check(
                journal.recordFrozen(
                    AgentFrozenInvocation(
                        command = command,
                        descriptor = descriptor,
                        scope = AgentToolScope(
                            taskRoot = frozen.scope.taskRoot,
                            logicalPaths = frozen.scope.logicalPaths,
                            expectedOldHash = frozen.scope.expectedOldHash,
                            networkDestinations = frozen.scope.networkDestinations
                        ),
                        recordedAtMillis = NOW
                    )
                ) is AgentToolInvocationRecord.Recorded
            )
            if (markWaiting) {
                check(
                    journal.markWaitingApproval(command.invocationId, NOW) is
                        AgentToolInvocationUpdate.Updated
                )
            }
        }
    }

    private suspend fun seedJournal(): Seed {
        val journal = InMemoryAgentJournal()
        val task = task()
        journal.commit(CreateTaskTransition("setup-task", task))
        val run = run(task)
        journal.commit(
            CreateRunTransition(
                "setup-run",
                run,
                taskRevision = task.revision,
                taskDisplay = AgentDisplayStatus.RUNNING
            )
        )
        return Seed(journal, task, journal.readRun(run.runId)!!.run)
    }

    private fun freezeFor(seed: Seed): AgentFreezeResult.Frozen {
        val result = factory.freeze(
            AgentFreezeRequest(
                descriptor = descriptor,
                rawArgumentsJson = ARGUMENTS_JSON,
                invocationId = "invocation-1",
                runId = seed.run.runId,
                stepId = "step-1",
                callOrdinal = 0,
                workspaceId = seed.task.workspaceId,
                taskId = seed.task.taskId,
                runEpoch = seed.run.epoch,
                policyVersion = seed.run.policy.policyVersion,
                nowMillis = NOW
            )
        )
        assertTrue("expected a frozen command, got $result", result is AgentFreezeResult.Frozen)
        return result as AgentFreezeResult.Frozen
    }

    private suspend fun approve(
        broker: AgentPermissionBroker,
        seed: Seed,
        frozen: AgentFreezeResult.Frozen
    ) = when (
        val decision = broker.decide(
            candidate = AgentPermissionCandidate(frozen.command, descriptor, frozen.scope),
            policy = AgentPermissionPolicy(
                seed.run.epoch,
                seed.run.policy.policyVersion,
                seed.run.policy.networkPolicy
            ),
            grant = AgentWorkspaceReadGrant.v1(seed.task),
            nowMillis = NOW
        )
    ) {
        is AgentPermissionDecision.NeedsApproval -> decision.approval
        else -> error("expected a pending approval, got $decision")
    }

    private fun task(): AgentTask = AgentTask(
        taskId = "task-1",
        workspaceId = "workspace-1",
        goal = "Write artifacts/report.md",
        lifecycle = AgentTaskLifecycle.ACTIVE,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        displayStatus = AgentDisplayStatus.IDLE,
        lastRunId = null,
        revision = 0L
    )

    private fun run(task: AgentTask): AgentRun = AgentRun(
        runId = "run-1",
        taskId = task.taskId,
        runOrdinal = 1L,
        state = AgentRunState.RUNNING,
        reason = AgentPauseReason.NONE,
        epoch = 1L,
        modelBinding = binding(),
        policy = AgentRunPolicy(),
        budget = AgentBudget(),
        spentBudget = AgentSpentBudget(),
        lastSequence = 0L,
        revision = 1L,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )

    private fun binding(): AgentModelBinding = AgentModelBinding(
        providerId = "local",
        modelId = "fixture-model",
        runtime = "FIXTURE",
        assetFingerprint = "sha256:fixture",
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Fixture",
        contextWindowTokens = 8_192
    )

    /**
     * Executor that creates the real durable invocation + approval for the actual run on its first
     * call, returns WaitingApproval, then records the authorized resume on the second call.
     */
    private class ApprovalResumeExecutor(
        private val journal: InMemoryAgentJournal,
        private val broker: AgentPermissionBroker,
        private val factory: AgentFrozenCommandFactory,
        private val descriptor: AgentToolDescriptor,
        private val validator: SupportedToolSchemaValidator
    ) : AgentRunExecutor {
        var executeCount: Int = 0
            private set
        val resumes = mutableListOf<AgentLoopResume?>()
        var approval: com.muyuchat.core.agent.contract.AgentApproval? = null

        override suspend fun execute(
            run: AgentRun,
            task: AgentTask,
            resume: AgentLoopResume?,
            progress: AgentModelProgressSink
        ): AgentLoopResult {
            executeCount += 1
            resumes += resume
            if (resume == null) {
                val frozen = when (
                    val result = factory.freeze(
                        AgentFreezeRequest(
                            descriptor = descriptor,
                            rawArgumentsJson = ARGUMENTS_JSON,
                            invocationId = "invocation-owner-1",
                            runId = run.runId,
                            stepId = "step-owner-1",
                            callOrdinal = 0,
                            workspaceId = task.workspaceId,
                            taskId = task.taskId,
                            runEpoch = run.epoch,
                            policyVersion = run.policy.policyVersion,
                            nowMillis = NOW
                        )
                    )
                ) {
                    is AgentFreezeResult.Frozen -> result
                    is AgentFreezeResult.Rejected -> error("freeze rejected: ${result.error.code}")
                }
                journal.recordProposed(
                    AgentToolProposal(
                        invocationId = frozen.command.invocationId,
                        runId = run.runId,
                        taskId = task.taskId,
                        stepId = frozen.command.stepId,
                        stepIndex = 1,
                        callOrdinal = 0,
                        toolKey = frozen.command.toolKey,
                        toolVersion = frozen.command.toolVersion,
                        schemaHash = frozen.command.schemaHash,
                        argumentsHash = "b".repeat(64),
                        proposedAtMillis = NOW
                    )
                )
                journal.recordFrozen(
                    AgentFrozenInvocation(
                        command = frozen.command,
                        descriptor = descriptor,
                        scope = AgentToolScope(
                            taskRoot = frozen.scope.taskRoot,
                            logicalPaths = frozen.scope.logicalPaths,
                            expectedOldHash = frozen.scope.expectedOldHash,
                            networkDestinations = frozen.scope.networkDestinations
                        ),
                        recordedAtMillis = NOW
                    )
                )
                journal.markWaitingApproval(frozen.command.invocationId, NOW)
                val decision = broker.decide(
                    candidate = AgentPermissionCandidate(frozen.command, descriptor, frozen.scope),
                    policy = AgentPermissionPolicy(
                        run.epoch,
                        run.policy.policyVersion,
                        run.policy.networkPolicy
                    ),
                    grant = AgentWorkspaceReadGrant.v1(task),
                    nowMillis = NOW
                )
                approval = (decision as AgentPermissionDecision.NeedsApproval).approval
                return AgentLoopResult.WaitingApproval(
                    run = run,
                    transcript = AgentLoopTranscript(emptyList(), 1, 0),
                    invocationId = frozen.command.invocationId,
                    callHash = "c".repeat(64),
                    approval = approval
                )
            }
            val decision = resume.toolDecision
            if (decision !is AgentResumeToolDecision.Authorized) {
                return AgentLoopResult.Failed(
                    run = run,
                    transcript = resume.transcript,
                    error = com.muyuchat.core.agent.contract.AgentError(
                        code = AgentErrorCodes.INTERNAL_INVARIANT,
                        category = com.muyuchat.core.agent.contract.AgentErrorCategory.INTERNAL,
                        retryability = com.muyuchat.core.agent.contract.AgentRetryability.NEVER,
                        safeMessage = "expected an authorized resume",
                        correlationId = "closure-test"
                    )
                )
            }
            return AgentLoopResult.Completed(
                run = run,
                transcript = AgentLoopTranscript(emptyList(), 2, 0),
                finalText = "done",
                artifactIds = emptyList()
            )
        }
    }

    private companion object {
        const val NOW = 1_000_000L
        const val ARGUMENTS_JSON =
            """{"path":"artifacts/report.md","content":"hello","expectedOldHash":null}"""
    }
}

package com.muyuchat.core.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract-level invariants (K01 A02/A03/A04 readiness). No runtime behavior is claimed here. */
class AgentContractTypesTest {

    private fun binding(
        protocol: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1,
        runtime: String = "MNN_CPU"
    ) = AgentModelBinding(
        providerId = "local",
        modelId = "mnn-qwen3-4b",
        runtime = runtime,
        assetFingerprint = "sha256:abc",
        protocolMode = protocol,
        modelDisplayName = "Qwen3 4B"
    )

    private fun task() = AgentTask(
        taskId = "task-1",
        workspaceId = "workspace-1",
        goal = "整理报告",
        lifecycle = AgentTaskLifecycle.ACTIVE,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        displayStatus = AgentDisplayStatus.IDLE,
        lastRunId = null,
        revision = 0L
    )

    private fun run(
        state: AgentRunState = AgentRunState.QUEUED,
        revision: Long = 0L
    ) = AgentRun(
        runId = "run-1",
        taskId = "task-1",
        runOrdinal = 1L,
        state = state,
        reason = AgentPauseReason.NONE,
        epoch = 1L,
        modelBinding = binding(),
        policy = AgentRunPolicy(),
        budget = AgentBudget(),
        spentBudget = AgentSpentBudget(),
        lastSequence = 0L,
        revision = revision,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )

    @Test
    fun modelChoiceDefaultsToLoadedAndCanCarryUnloadedState() {
        val loaded = AgentModelChoice(
            providerId = "local",
            modelId = "mnn-qwen3-4b",
            displayName = "Qwen3 4B",
            runtime = "MNN_CPU",
            protocolMode = AgentProtocolMode.PROMPTED_V1,
            assetFingerprint = "sha256:abc",
            availableOffline = true
        )
        // Pre-existing catalog implementations that omit the field keep their runnable meaning.
        assertEquals(AgentModelLoadState.LOADED, loaded.loadState)
        val unloaded = loaded.copy(loadState = AgentModelLoadState.UNLOADED)
        assertEquals(AgentModelLoadState.UNLOADED, unloaded.loadState)
        // AgentModelChoice is a read-only port projection, not a persisted/versioned record.
        assertEquals(1, AgentContract.SCHEMA_VERSION)
    }

    @Test
    fun everyPersistedRecordDefaultsToSchemaVersionOne() {
        assertEquals(1, AgentContract.SCHEMA_VERSION)
        assertEquals(1, task().schemaVersion)
        assertEquals(1, run().schemaVersion)
        assertEquals(1, binding().let { 1 })
        val attempt = AgentModelAttempt(
            attemptId = "attempt-1",
            runId = "run-1",
            stepIndex = 0,
            ordinal = 1,
            backendRequestId = "agent:run-1:0:1",
            backendIdentityRef = null,
            bindingFingerprint = "binding-hash",
            startedAtMillis = 1_000L,
            terminal = AgentAttemptTerminal.RUNNING
        )
        assertEquals(1, attempt.schemaVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskRejectsBlankGoal() {
        task().copy(goal = "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskRejectsNonStableId() {
        task().copy(taskId = "task id with spaces")
    }

    @Test(expected = IllegalArgumentException::class)
    fun taskRejectsUpdatedTimestampBeforeCreation() {
        task().copy(createdAtMillis = 2_000L, updatedAtMillis = 1_000L)
    }

    @Test
    fun budgetDefaultsMatchFrozenContract() {
        val budget = AgentBudget()
        assertEquals(12, budget.modelAttempts)
        assertEquals(8, budget.toolInvocations)
        assertEquals(1, budget.callsPerModelResponse)
        assertEquals(1, budget.protocolRepairs)
        assertEquals(180_000L, budget.modelWallTimeMillis)
        assertEquals(30_000L, budget.toolTimeoutMillis)
        assertEquals(900_000L, budget.runActiveTimeMillis)
        assertEquals(120_000L, budget.resourceWaitMillis)
        assertEquals(16 * 1024, budget.toolResultToModelBytes)
        assertEquals(64 * 1024, budget.toolResultPersistBytes)
        assertEquals(2_048, budget.textOutputTokens)
        assertEquals(24_576, budget.totalGeneratedTokens)
    }

    @Test(expected = IllegalArgumentException::class)
    fun budgetRejectsParallelToolCalls() {
        AgentBudget(callsPerModelResponse = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun budgetRejectsUnboundedRepairs() {
        AgentBudget(protocolRepairs = 2)
    }

    @Test
    fun terminalStatesNeverHoldActiveSlot() {
        val terminal = listOf(
            AgentRunState.SUCCEEDED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.SUPERSEDED
        )
        terminal.forEach {
            assertTrue(it.isTerminal)
            assertFalse(it.holdsActiveSlot)
        }
    }

    @Test
    fun waitingAndStoppingStatesHoldActiveSlot() {
        listOf(
            AgentRunState.QUEUED,
            AgentRunState.RUNNING,
            AgentRunState.WAITING_RESOURCE,
            AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_USER,
            AgentRunState.PAUSING,
            AgentRunState.STOPPING
        ).forEach {
            assertFalse(it.isTerminal)
            assertTrue(it.holdsActiveSlot)
        }
    }

    @Test
    fun pausedAndReviewStatesReleaseActiveSlot() {
        listOf(AgentRunState.PAUSED, AgentRunState.NEEDS_REVIEW, AgentRunState.INTERRUPTED).forEach {
            assertFalse(it.isTerminal)
            assertFalse(it.holdsActiveSlot)
        }
    }

    @Test
    fun approvalTtlIsFiveMinutes() {
        assertEquals(5 * 60 * 1000L, AgentContract.APPROVAL_TTL_MILLIS)
    }

    @Test
    fun artifactRejectsEscapingPaths() {
        val artifact = AgentArtifact(
            artifactId = "artifact-1",
            taskId = "task-1",
            invocationId = "invocation-1",
            relativePath = "artifacts/report.md",
            mediaType = "text/markdown",
            sizeBytes = 10,
            sha256 = "a".repeat(64),
            commitState = AgentArtifactCommitState.COMMITTED,
            createdAtMillis = 1_000L
        )
        assertEquals("artifacts/report.md", artifact.relativePath)
        listOf("/etc/passwd", "artifacts/../../secret", "../secret").forEach { bad ->
            var threw = false
            try {
                artifact.copy(relativePath = bad)
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("path must be rejected: $bad", threw)
        }
    }

    @Test
    fun workspaceCommandsStayNarrow() {
        val methods = AgentWorkspaceCommands::class.java.declaredMethods
        assertEquals(7, methods.size)
        assertNotEquals(0, AgentWorkspaceQueries::class.java.declaredMethods.size)
    }

    @Test
    fun protocolNameAndLimitsAreFrozen() {
        assertEquals("mca.agent.v1", AgentContract.PROTOCOL_NAME)
        assertEquals(64 * 1024, AgentContract.MAX_CONTROL_RESPONSE_BYTES)
        assertEquals(16, AgentContract.MAX_JSON_DEPTH)
        assertEquals(1, AgentContract.MAX_ACTIVE_RUNS_PER_PROCESS)
        assertEquals(5, AgentContract.MAX_QUEUED_RUNS)
    }

    @Test
    fun stableIdsRejectDisplayText() {
        assertTrue(AgentContract.isValidId("run-1"))
        assertTrue(AgentContract.isValidId("9f8e7d6c-0000-4000-8000-000000000000"))
        assertFalse(AgentContract.isValidId(""))
        assertFalse(AgentContract.isValidId("   "))
        assertFalse(AgentContract.isValidId("a/b"))
        assertFalse(AgentContract.isValidId("报告"))
    }

    @Test
    fun networkPolicyCannotBeInconsistent() {
        val offline = AgentRunPolicy(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY)
        assertTrue(offline.offlineForced)
        var threw = false
        try {
            AgentRunPolicy(networkPolicy = AgentNetworkPolicy.LOCAL_ONLY, offlineForced = false)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun pauseCommandDefaultsToUserRequestedAndAcceptsForegroundGuardReason() {
        val sourceCompatible = PauseAgentRun("cmd-pause-default", "run-1", 0L)
        assertEquals(AgentPauseReason.USER_REQUESTED, sourceCompatible.reason)
        assertEquals(0L, sourceCompatible.expectedRunRevision)

        val foreground = PauseAgentRun(
            commandId = "cmd-pause-fgs",
            runId = "run-1",
            expectedRunRevision = 3L,
            reason = AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
        )
        assertEquals(AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE, foreground.reason)
    }

    @Test
    fun pauseCommandRejectsNonPausableReasonsBeforeAnyOwnerCall() {
        val rejected = listOf(
            AgentPauseReason.NONE,
            AgentPauseReason.RESOURCE_BUSY,
            AgentPauseReason.APPROVAL_EXPIRED,
            AgentPauseReason.APPROVAL_STALE,
            AgentPauseReason.MODEL_PROTOCOL_ERROR,
            AgentPauseReason.MODEL_BINDING_CHANGED,
            AgentPauseReason.CONTEXT_LIMIT,
            AgentPauseReason.PERMISSION_DENIED_LOOP,
            AgentPauseReason.BUDGET_LIMIT,
            AgentPauseReason.INTERRUPTED,
            AgentPauseReason.UNKNOWN
        )
        rejected.forEach { reason ->
            var threw = false
            try {
                PauseAgentRun("cmd-pause-rejected", "run-1", 0L, reason)
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("PauseAgentRun must reject reason ${reason.name}", threw)
        }
    }

    @Test
    fun pauseCommandStillValidatesIdentityAndRevision() {
        val invalidId = runCatching { PauseAgentRun("bad id", "run-1", 0L) }
        assertTrue(invalidId.isFailure)
        val negativeRevision = runCatching { PauseAgentRun("cmd-pause", "run-1", -1L) }
        assertTrue(negativeRevision.isFailure)
    }
}

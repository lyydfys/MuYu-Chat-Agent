package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentStepKind
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.SupersedeRunTransition
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.contract.UpsertArtifactTransition
import com.muyuchat.core.agent.contract.UpsertAttemptTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import com.muyuchat.core.agent.contract.UpsertStepTransition
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic in-memory [AgentJournal] with the same contract as the future Room implementation:
 *
 * - every commit is atomic and serialized under one mutex (no I/O inside the critical section);
 * - commandId idempotency is checked before any revision CAS: identical content replays the
 *   recorded result, different content returns COMMAND_IDEMPOTENCY_CONFLICT;
 * - revisions must advance exactly one step, event sequences are per-run and strictly monotonic,
 *   terminal runs are immutable and only [SupersedeRunTransition] may record a terminal run as
 *   SUPERSEDED;
 * - one non-terminal run per task and one successor run per continue.
 *
 * It also implements the narrow reader/ledger ports K03 needs but the frozen [AgentJournal] does
 * not expose; K05's Room implementation must implement the same ports.
 */
class InMemoryAgentJournal : AgentJournal, AgentTaskReader, AgentQuestionReader, AgentApprovalReader, AgentArtifactReader,
    AgentCommandLedger, AgentToolInvocationLedger {

    internal val mutex = Mutex()
    private val tasks = LinkedHashMap<String, AgentTask>()
    internal val runs = LinkedHashMap<String, AgentRun>()
    private val events = LinkedHashMap<String, MutableList<AgentEvent>>()
    internal val steps = LinkedHashMap<String, LinkedHashMap<String, AgentStep>>()
    private val attempts = LinkedHashMap<String, LinkedHashMap<String, AgentModelAttempt>>()
    internal val invocations = LinkedHashMap<String, LinkedHashMap<String, ToolInvocation>>()
    internal val frozenInvocations = LinkedHashMap<String, AgentFrozenInvocation>()
    private val approvals = LinkedHashMap<String, AgentApproval>()
    private val questions = LinkedHashMap<String, AgentQuestion>()
    private val artifacts = LinkedHashMap<String, AgentArtifact>()
    private val artifactCommits = LinkedHashMap<String, AgentArtifactCommit>()

    /** commandId -> (transition fingerprint, result). */
    private val transitionResults = LinkedHashMap<String, Pair<String, AgentCommitResult>>()

    /** Owner-level command records (idempotency before revision validation). */
    private val commandLedger = LinkedHashMap<String, AgentCommandRecord>()

    override suspend fun commit(command: AgentTransition): AgentCommitResult = mutex.withLock {
        val fingerprint = AgentHashes.sha256HexUtf8(command.toString())
        transitionResults[command.commandId]?.let { (recorded, result) ->
            if (recorded != fingerprint) idempotencyConflict(command.commandId)
            return@withLock result.copy(idempotentReplay = true)
        }
        val result = applyTransition(command)
        transitionResults[command.commandId] = fingerprint to result
        result
    }

    override suspend fun readRun(runId: String): AgentRunSnapshot? = mutex.withLock {
        val run = runs[runId] ?: return@withLock null
        val task = tasks[run.taskId] ?: return@withLock null
        val runInvocations = invocations[runId]?.keys.orEmpty()
        AgentRunSnapshot(
            run = run,
            task = task,
            activeQuestion = questions.values
                .filter { it.runId == runId && it.status == AgentQuestionStatus.OPEN }
                .maxByOrNull { it.askedAtMillis },
            pendingApproval = approvals.values
                .filter { it.status == AgentApprovalStatus.PENDING && it.invocationId in runInvocations }
                .maxByOrNull { it.createdAtMillis },
            steps = steps[runId]?.values?.sortedBy { it.index }.orEmpty(),
            artifacts = artifacts.values.filter { it.taskId == run.taskId }
        )
    }

    override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> = mutex.withLock {
        val bounded = limit.coerceIn(1, 200)
        runs.values
            .filterNot { it.state.isTerminal }
            .sortedBy { it.createdAtMillis }
            .take(bounded)
            .mapNotNull { run -> snapshotLocked(run) }
    }

    override suspend fun readEvents(
        runId: String,
        afterSequence: Long,
        limit: Int
    ): AgentEventPage = mutex.withLock {
        val all = events[runId].orEmpty().filter { it.sequence > afterSequence }
            .sortedBy { it.sequence }
        val bounded = limit.coerceIn(1, 500)
        val page = all.take(bounded)
        AgentEventPage(
            events = page,
            nextSequence = if (all.size > page.size) page.lastOrNull()?.sequence else null
        )
    }

    override suspend fun readTask(taskId: String): AgentTask? = mutex.withLock { tasks[taskId] }

    override suspend fun readQuestion(questionId: String): AgentQuestion? =
        mutex.withLock { questions[questionId] }

    override suspend fun readApproval(approvalId: String): AgentApproval? =
        mutex.withLock { approvals[approvalId] }

    override suspend fun readArtifacts(taskId: String): List<AgentArtifact> =
        mutex.withLock { artifacts.values.filter { it.taskId == taskId } }

    override suspend fun findCommand(commandId: String): AgentCommandRecord? =
        mutex.withLock { commandLedger[commandId] }

    override suspend fun recordCommand(record: AgentCommandRecord) = mutex.withLock {
        val existing = commandLedger[record.commandId]
        if (existing != null) {
            if (existing.contentHash != record.contentHash) {
                idempotencyConflict(record.commandId)
            }
            return@withLock
        }
        commandLedger[record.commandId] = record
    }

    private val toolLedger = InMemoryAgentToolInvocationLedger(this)

    override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord =
        toolLedger.recordProposed(proposal)

    override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord =
        toolLedger.recordFrozen(frozen)

    override suspend fun markWaitingApproval(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = toolLedger.markWaitingApproval(invocationId, atMillis)

    override suspend fun markApproved(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = toolLedger.markApproved(invocationId, atMillis)

    override suspend fun markRunning(
        invocationId: String,
        atMillis: Long
    ): AgentToolInvocationUpdate = toolLedger.markRunning(invocationId, atMillis)

    override suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate = toolLedger.markTerminal(
        invocationId,
        status,
        possibleSideEffects,
        resultRef,
        atMillis
    )

    override suspend fun readInvocation(invocationId: String): ToolInvocation? =
        toolLedger.readInvocation(invocationId)

    override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? =
        toolLedger.readFrozenInvocation(invocationId)

    /** Test visibility: all tasks in creation order. */
    suspend fun allTasks(): List<AgentTask> = mutex.withLock { tasks.values.toList() }

    /** Test visibility: all events for a run, ordered. */
    suspend fun allEvents(runId: String): List<AgentEvent> =
        mutex.withLock { events[runId].orEmpty().sortedBy { it.sequence } }

    /** Test visibility: every recorded owner command. */
    suspend fun commandRecords(): List<AgentCommandRecord> =
        mutex.withLock { commandLedger.values.toList() }

    private fun snapshotLocked(run: AgentRun): AgentRunSnapshot? {
        val task = tasks[run.taskId] ?: return null
        val runInvocations = invocations[run.runId]?.keys.orEmpty()
        return AgentRunSnapshot(
            run = run,
            task = task,
            activeQuestion = questions.values
                .filter { it.runId == run.runId && it.status == AgentQuestionStatus.OPEN }
                .maxByOrNull { it.askedAtMillis },
            pendingApproval = approvals.values
                .filter { it.status == AgentApprovalStatus.PENDING && it.invocationId in runInvocations }
                .maxByOrNull { it.createdAtMillis },
            steps = steps[run.runId]?.values?.sortedBy { it.index }.orEmpty(),
            artifacts = artifacts.values.filter { it.taskId == run.taskId }
        )
    }

    private fun applyTransition(transition: AgentTransition): AgentCommitResult = when (transition) {
        is CreateTaskTransition -> createTask(transition)
        is CreateRunTransition -> createRun(transition)
        is UpdateRunTransition -> updateRun(transition)
        is AppendEventsTransition -> appendEvents(transition)
        is UpsertStepTransition -> upsertStep(transition)
        is UpsertAttemptTransition -> upsertAttempt(transition)
        is UpsertInvocationTransition -> upsertInvocation(transition)
        is UpsertApprovalTransition -> upsertApproval(transition)
        is ConsumeApprovalTransition -> consumeApproval(transition)
        is AskQuestionTransition -> askQuestion(transition)
        is AnswerQuestionTransition -> answerQuestion(transition)
        is UpsertArtifactTransition -> upsertArtifact(transition)
        is SupersedeRunTransition -> supersedeRun(transition)
    }

    private fun createTask(transition: CreateTaskTransition): AgentCommitResult {
        if (tasks.containsKey(transition.task.taskId)) {
            invalidState(transition.commandId, "Task '${transition.task.taskId}' already exists.")
        }
        tasks[transition.task.taskId] = transition.task
        return AgentCommitResult(
            appliedRevision = transition.task.revision,
            taskRevision = transition.task.revision
        )
    }

    private fun createRun(transition: CreateRunTransition): AgentCommitResult {
        val task = tasks[transition.run.taskId]
            ?: journalRejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Task '${transition.run.taskId}' not found.")
        if (task.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        if (runs.containsKey(transition.run.runId)) {
            invalidState(transition.commandId, "Run '${transition.run.runId}' already exists.")
        }
        if (transition.run.revision != 1L) {
            invalidState(transition.commandId, "A newly created run must start at revision 1.")
        }
        val successor = transition.successorOfRunId?.let { previousRunId ->
            runs[previousRunId] ?: journalRejected(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "Previous run '$previousRunId' not found."
            )
        }
        if (runs.values.any {
                it.taskId == task.taskId &&
                    !it.state.isTerminal &&
                    it.runId != successor?.runId
            }
        ) {
            invalidState(transition.commandId, "The task already has a non-terminal run.")
        }
        if (successor != null) {
            if (successor.taskId != task.taskId) {
                invalidState(transition.commandId, "A successor must belong to the same task.")
            }
            if (successor.state != AgentRunState.PAUSED && successor.state != AgentRunState.NEEDS_REVIEW) {
                invalidState(
                    transition.commandId,
                    "Only a PAUSED or NEEDS_REVIEW run can be superseded by a continue."
                )
            }
        } else if (task.lastRunId != null) {
            invalidState(
                transition.commandId,
                "This task already has a run; use ContinueAgentTask to create a successor."
            )
        }
        runs[transition.run.runId] = transition.run
        if (successor != null) {
            runs[successor.runId] = successor.copy(
                state = AgentRunState.SUPERSEDED,
                revision = successor.revision + 1,
                updatedAtMillis = transition.run.updatedAtMillis
            )
        }
        val updatedTask = task.copy(
            lastRunId = transition.run.runId,
            displayStatus = transition.taskDisplay,
            revision = task.revision + 1,
            updatedAtMillis = transition.run.updatedAtMillis
        )
        tasks[task.taskId] = updatedTask
        return AgentCommitResult(
            appliedRevision = transition.run.revision,
            taskRevision = updatedTask.revision,
            runRevision = transition.run.revision
        )
    }

    private fun updateRun(transition: UpdateRunTransition): AgentCommitResult {
        val existing = requireRun(transition.run.runId)
        if (existing.state.isTerminal) {
            invalidState(transition.commandId, "Terminal run '${existing.runId}' is immutable.")
        }
        if (existing.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        if (transition.run.revision != existing.revision + 1L) {
            invalidState(transition.commandId, "A run revision must advance by exactly one.")
        }
        if (transition.run.epoch != existing.epoch) {
            invalidState(transition.commandId, "A run epoch cannot change within the same run.")
        }
        if (transition.run.taskId != existing.taskId) {
            invalidState(transition.commandId, "A run cannot change its task.")
        }
        val task = requireTask(existing.taskId)
        runs[existing.runId] = transition.run
        val updatedTask = task.copy(
            displayStatus = transition.taskDisplay,
            revision = task.revision + 1,
            updatedAtMillis = transition.run.updatedAtMillis
        )
        tasks[task.taskId] = updatedTask
        return AgentCommitResult(
            appliedRevision = transition.run.revision,
            taskRevision = updatedTask.revision,
            runRevision = transition.run.revision
        )
    }

    private fun appendEvents(transition: AppendEventsTransition): AgentCommitResult {
        val existing = requireRun(transition.runIdValue)
        if (transition.expectedRevision != null && existing.revision != transition.expectedRevision) {
            revisionConflict(transition.commandId)
        }
        if (transition.events.isEmpty()) {
            invalidState(transition.commandId, "An append transition needs at least one event.")
        }
        var sequence = existing.lastSequence
        val list = events.getOrPut(existing.runId) { ArrayList() }
        for (event in transition.events) {
            if (event.runId != existing.runId) {
                invalidState(transition.commandId, "Event '${event.eventId}' belongs to another run.")
            }
            if (event.sequence != sequence + 1L) {
                invalidState(
                    transition.commandId,
                    "Event sequence must be ${sequence + 1}, got ${event.sequence}."
                )
            }
            sequence = event.sequence
            list += event
        }
        val updated = existing.copy(
            lastSequence = sequence,
            revision = existing.revision + 1
        )
        runs[existing.runId] = updated
        return AgentCommitResult(
            appliedRevision = updated.revision,
            runRevision = updated.revision,
            eventSequences = transition.events.map { it.sequence }
        )
    }

    private fun upsertStep(transition: UpsertStepTransition): AgentCommitResult {
        val run = requireRun(transition.step.runId)
        if (run.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        val list = steps.getOrPut(run.runId) { LinkedHashMap() }
        val duplicateIndex = list.values.firstOrNull {
            it.index == transition.step.index && it.stepId != transition.step.stepId
        }
        if (duplicateIndex != null) {
            invalidState(transition.commandId, "Step index ${transition.step.index} already exists.")
        }
        list[transition.step.stepId] = transition.step
        return AgentCommitResult(appliedRevision = run.revision, runRevision = run.revision)
    }

    private fun upsertAttempt(transition: UpsertAttemptTransition): AgentCommitResult {
        val run = requireRun(transition.attempt.runId)
        if (run.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        val list = attempts.getOrPut(run.runId) { LinkedHashMap() }
        val duplicateOrdinal = list.values.firstOrNull {
            it.stepIndex == transition.attempt.stepIndex &&
                it.ordinal == transition.attempt.ordinal &&
                it.attemptId != transition.attempt.attemptId
        }
        if (duplicateOrdinal != null) {
            invalidState(
                transition.commandId,
                "Attempt ordinal ${transition.attempt.ordinal} already exists for step " +
                    "${transition.attempt.stepIndex}."
            )
        }
        list[transition.attempt.attemptId] = transition.attempt
        return AgentCommitResult(appliedRevision = run.revision, runRevision = run.revision)
    }

    private fun upsertInvocation(transition: UpsertInvocationTransition): AgentCommitResult {
        val run = requireRun(transition.invocation.runId)
        if (run.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        val duplicateKey = invocations.values.asSequence()
            .flatMap { it.values.asSequence() }
            .firstOrNull {
                it.idempotencyKey == transition.invocation.idempotencyKey &&
                    it.invocationId != transition.invocation.invocationId
            }
        if (duplicateKey != null) {
            invalidState(transition.commandId, "Idempotency key is already used by another invocation.")
        }
        val list = invocations.getOrPut(run.runId) { LinkedHashMap() }
        list[transition.invocation.invocationId] = transition.invocation
        return AgentCommitResult(appliedRevision = run.revision, runRevision = run.revision)
    }

    private fun upsertApproval(transition: UpsertApprovalTransition): AgentCommitResult {
        val approval = transition.approval
        val existing = approvals[approval.approvalId]
        if (existing != null && existing.status != AgentApprovalStatus.PENDING &&
            approval.status == AgentApprovalStatus.PENDING
        ) {
            invalidState(transition.commandId, "A consumed approval cannot be reopened.")
        }
        if (approval.status == AgentApprovalStatus.PENDING) {
            val otherPending = approvals.values.firstOrNull {
                it.approvalId != approval.approvalId &&
                    it.invocationId == approval.invocationId &&
                    it.status == AgentApprovalStatus.PENDING
            }
            if (otherPending != null) {
                invalidState(transition.commandId, "The invocation already has a pending approval.")
            }
        }
        approvals[approval.approvalId] = approval
        return AgentCommitResult(
            appliedRevision = transition.expectedRevision,
            runRevision = null
        )
    }

    private fun consumeApproval(transition: ConsumeApprovalTransition): AgentCommitResult {
        val approval = approvals[transition.approvalId] ?: journalRejected(
            AgentErrorCodes.COMMAND_NOT_FOUND,
            "Approval '${transition.approvalId}' not found."
        )
        if (approval.status != AgentApprovalStatus.PENDING) {
            invalidState(transition.commandId, "Approval '${approval.approvalId}' was already consumed.")
        }
        if (transition.consumedAtMillis > approval.expiresAtMillis) {
            journalRejected(
                AgentErrorCodes.PERMISSION_EXPIRED,
                "Approval '${approval.approvalId}' expired before it was consumed."
            )
        }
        val nextRun = transition.nextRun
        var updatedTask: AgentTask? = null
        if (nextRun != null) {
            val existing = requireRun(nextRun.runId)
            if (existing.state.isTerminal) {
                invalidState(transition.commandId, "Cannot resume a terminal run.")
            }
            if (existing.state != AgentRunState.WAITING_APPROVAL) {
                invalidState(transition.commandId, "The run is not waiting for approval.")
            }
            if (existing.revision != transition.expectedRevision) revisionConflict(transition.commandId)
            if (nextRun.revision != existing.revision + 1L) {
                invalidState(transition.commandId, "A run revision must advance by exactly one.")
            }
            if (nextRun.epoch != existing.epoch) {
                invalidState(transition.commandId, "A run epoch cannot change within the same run.")
            }
            val task = requireTask(existing.taskId)
            runs[nextRun.runId] = nextRun
            updatedTask = task.copy(
                displayStatus = transition.taskDisplay ?: task.displayStatus,
                revision = task.revision + 1,
                updatedAtMillis = transition.consumedAtMillis
            )
            tasks[task.taskId] = updatedTask
        }
        approvals[approval.approvalId] = approval.copy(
            status = AgentApprovalStatus.CONSUMED,
            consumedAtMillis = transition.consumedAtMillis
        )
        return AgentCommitResult(
            appliedRevision = nextRun?.revision ?: transition.expectedRevision,
            taskRevision = updatedTask?.revision,
            runRevision = nextRun?.revision
        )
    }

    private fun askQuestion(transition: AskQuestionTransition): AgentCommitResult {
        val existing = requireRun(transition.nextRun.runId)
        if (existing.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        if (existing.state != AgentRunState.RUNNING) {
            invalidState(transition.commandId, "A question can only be asked from RUNNING.")
        }
        if (transition.question.runId != existing.runId) {
            invalidState(transition.commandId, "The question belongs to another run.")
        }
        if (transition.question.status != AgentQuestionStatus.OPEN) {
            invalidState(transition.commandId, "A new question must be OPEN.")
        }
        if (questions.containsKey(transition.question.questionId)) {
            invalidState(transition.commandId, "Question id already exists.")
        }
        if (questions.values.any { it.runId == existing.runId && it.status == AgentQuestionStatus.OPEN }) {
            invalidState(transition.commandId, "The run already has an open question.")
        }
        val nextRun = transition.nextRun
        if (nextRun.state != AgentRunState.WAITING_USER || nextRun.revision != existing.revision + 1L) {
            invalidState(transition.commandId, "Ask-question must move the run to WAITING_USER.")
        }
        if (nextRun.epoch != existing.epoch) {
            invalidState(transition.commandId, "A run epoch cannot change within the same run.")
        }
        questions[transition.question.questionId] = transition.question
        runs[nextRun.runId] = nextRun
        val task = requireTask(existing.taskId)
        val updatedTask = task.copy(
            displayStatus = transition.taskDisplay,
            revision = task.revision + 1,
            updatedAtMillis = transition.question.askedAtMillis
        )
        tasks[task.taskId] = updatedTask
        return AgentCommitResult(
            appliedRevision = nextRun.revision,
            taskRevision = updatedTask.revision,
            runRevision = nextRun.revision
        )
    }

    private fun answerQuestion(transition: AnswerQuestionTransition): AgentCommitResult {
        val question = questions[transition.questionId] ?: journalRejected(
            AgentErrorCodes.COMMAND_NOT_FOUND,
            "Question '${transition.questionId}' not found."
        )
        if (question.status != AgentQuestionStatus.OPEN) {
            invalidState(transition.commandId, "Question '${transition.questionId}' was already answered.")
        }
        val existing = requireRun(transition.nextRun.runId)
        if (question.runId != existing.runId) {
            invalidState(transition.commandId, "The question belongs to another run.")
        }
        if (existing.state != AgentRunState.WAITING_USER) {
            invalidState(transition.commandId, "The run is not waiting for a user answer.")
        }
        if (existing.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        if (transition.nextRun.revision != existing.revision + 1L) {
            invalidState(transition.commandId, "A run revision must advance by exactly one.")
        }
        if (transition.nextRun.state != AgentRunState.RUNNING) {
            invalidState(transition.commandId, "Answering a question resumes the same run as RUNNING.")
        }
        questions[transition.questionId] = question.copy(
            status = AgentQuestionStatus.ANSWERED,
            answer = transition.answer,
            answeredAtMillis = transition.answeredAtMillis
        )
        runs[transition.nextRun.runId] = transition.nextRun
        val task = requireTask(existing.taskId)
        val updatedTask = task.copy(
            displayStatus = transition.taskDisplay,
            revision = task.revision + 1,
            updatedAtMillis = transition.answeredAtMillis
        )
        tasks[task.taskId] = updatedTask
        return AgentCommitResult(
            appliedRevision = transition.nextRun.revision,
            taskRevision = updatedTask.revision,
            runRevision = transition.nextRun.revision
        )
    }

    private fun upsertArtifact(transition: UpsertArtifactTransition): AgentCommitResult {
        val task = tasks[transition.artifact.taskId] ?: journalRejected(
            AgentErrorCodes.COMMAND_NOT_FOUND,
            "Task '${transition.artifact.taskId}' not found."
        )
        artifacts[transition.artifact.artifactId] = transition.artifact
        transition.commit?.let { artifactCommits[it.commitId] = it }
        return AgentCommitResult(
            appliedRevision = task.revision,
            taskRevision = task.revision
        )
    }

    private fun supersedeRun(transition: SupersedeRunTransition): AgentCommitResult {
        val previous = requireRun(transition.previousRunId)
        if (previous.revision != transition.expectedRevision) revisionConflict(transition.commandId)
        if (transition.updatedRun.runId != previous.runId) {
            invalidState(transition.commandId, "The superseded record must match the previous run.")
        }
        if (transition.updatedRun.state != AgentRunState.SUPERSEDED) {
            invalidState(transition.commandId, "A supersede transition must record SUPERSEDED.")
        }
        if (transition.updatedRun.revision != previous.revision + 1L) {
            invalidState(transition.commandId, "A run revision must advance by exactly one.")
        }
        if (!previous.state.isTerminal &&
            previous.state != AgentRunState.PAUSED &&
            previous.state != AgentRunState.NEEDS_REVIEW
        ) {
            invalidState(transition.commandId, "Only settled runs can be superseded.")
        }
        runs[previous.runId] = transition.updatedRun
        return AgentCommitResult(
            appliedRevision = transition.updatedRun.revision,
            runRevision = transition.updatedRun.revision
        )
    }

    private fun requireRun(runId: String): AgentRun = runs[runId] ?: journalRejected(
        AgentErrorCodes.COMMAND_NOT_FOUND,
        "Run '$runId' not found."
    )

    private fun requireTask(taskId: String): AgentTask = tasks[taskId] ?: journalRejected(
        AgentErrorCodes.COMMAND_NOT_FOUND,
        "Task '$taskId' not found."
    )
}

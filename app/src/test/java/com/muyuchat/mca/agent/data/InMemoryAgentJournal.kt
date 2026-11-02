package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.SupersedeRunTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertStepTransition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory journal with the same idempotency/CAS/sequence contract as [RoomAgentJournal].
 * It exists so the transaction semantics can be exercised on a plain JVM (no Robolectric/Room).
 */
class InMemoryAgentJournal(
    private val clock: AgentClock = AgentClock { counter++ }
) : AgentJournal {
    private data class StoredCommand(val contentHash: String, val result: AgentCommitResult)

    private val mutex = Mutex()
    private val commands = linkedMapOf<String, StoredCommand>()
    private val tasks = linkedMapOf<String, AgentTask>()
    private val runs = linkedMapOf<String, AgentRun>()
    private val events = linkedMapOf<String, MutableList<AgentEvent>>()
    private val steps = linkedMapOf<String, AgentStep>()
    private val artifacts = linkedMapOf<String, AgentArtifact>()

    override suspend fun readRun(runId: String): AgentRunSnapshot? = mutex.withLock {
        val run = runs[runId] ?: return@withLock null
        val task = tasks[run.taskId] ?: return@withLock null
        AgentRunSnapshot(
            run = run,
            task = task,
            steps = steps.values.filter { it.runId == runId }.sortedBy { it.index },
            artifacts = artifacts.values.filter { artifact ->
                artifact.taskId == run.taskId
            }
        )
    }

    override suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot> = mutex.withLock {
        require(limit in 1..200)
        runs.values
            .filterNot { it.state.isTerminal }
            .mapNotNull { run ->
                val task = tasks[run.taskId] ?: return@mapNotNull null
                if (task.lifecycle != AgentTaskLifecycle.ACTIVE) return@mapNotNull null
                AgentRunSnapshot(run = run, task = task)
            }
            .sortedByDescending { it.run.updatedAtMillis }
            .take(limit)
    }

    override suspend fun readEvents(
        runId: String,
        afterSequence: Long,
        limit: Int
    ): AgentEventPage = mutex.withLock {
        require(limit in 1..200)
        val matching = events[runId].orEmpty()
            .filter { it.sequence > afterSequence }
            .sortedBy { it.sequence }
            .take(limit)
        AgentEventPage(events = matching, nextSequence = matching.lastOrNull()?.sequence)
    }

    override suspend fun commit(command: AgentTransition): AgentCommitResult = mutex.withLock {
        val fingerprint = AgentCommandFingerprint.fingerprint(command)
        val existing = commands[command.commandId]
        when (AgentJournalRules.commandLookup(existing?.contentHash, fingerprint)) {
            AgentJournalRules.CommandLookup.CONFLICT -> throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
                "conflicting content for ${command.commandId}"
            )
            AgentJournalRules.CommandLookup.REPLAY -> existing!!.result.copy(idempotentReplay = true)
            AgentJournalRules.CommandLookup.EXECUTE -> {
                val result = apply(command)
                commands[command.commandId] = StoredCommand(fingerprint, result)
                result
            }
        }
    }

    private fun apply(command: AgentTransition): AgentCommitResult = when (command) {
        is CreateTaskTransition -> {
            if (tasks.containsKey(command.task.taskId)) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "task already exists"
                )
            }
            tasks[command.task.taskId] = command.task
            AgentCommitResult(appliedRevision = command.task.revision, taskRevision = command.task.revision)
        }
        is CreateRunTransition -> {
            val task = tasks[command.run.taskId]
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "task missing")
            AgentJournalRules.requireRevision(task.revision, command.taskRevision, "task")
            command.successorOfRunId?.let { previousId ->
                val previous = runs[previousId]
                    ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "run missing")
                runs[previousId] = previous.copy(
                    state = AgentRunState.SUPERSEDED,
                    revision = previous.revision + 1,
                    updatedAtMillis = clock.nowMillis()
                )
            }
            runs[command.run.runId] = command.run
            tasks[task.taskId] = task.copy(
                displayStatus = command.taskDisplay,
                lastRunId = command.run.runId,
                revision = task.revision + 1,
                updatedAtMillis = clock.nowMillis()
            )
            AgentCommitResult(appliedRevision = command.run.revision, runRevision = command.run.revision)
        }
        is UpdateRunTransition -> {
            val current = runs[command.run.runId]
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "run missing")
            AgentJournalRules.requireRevision(current.revision, command.expectedRevision, "run")
            AgentJournalRules.requireTransitionAllowed(current.state, command.run.state)
            val updated = command.run.copy(
                revision = current.revision + 1,
                updatedAtMillis = clock.nowMillis()
            )
            runs[updated.runId] = updated
            val task = tasks[updated.taskId]
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "task missing")
            tasks[task.taskId] = task.copy(
                displayStatus = command.taskDisplay,
                revision = task.revision + 1,
                updatedAtMillis = clock.nowMillis()
            )
            AgentCommitResult(appliedRevision = updated.revision, runRevision = updated.revision)
        }
        is AppendEventsTransition -> {
            val current = runs[command.runIdValue]
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "run missing")
            command.expectedRevision?.let {
                AgentJournalRules.requireRevision(current.revision, it, "run")
            }
            val sequences = AgentJournalRules.validateEventSequences(current.lastSequence, command.events)
            events.getOrPut(current.runId) { mutableListOf() }.addAll(command.events)
            val updated = current.copy(
                lastSequence = sequences.last(),
                revision = current.revision + 1,
                updatedAtMillis = clock.nowMillis()
            )
            runs[current.runId] = updated
            AgentCommitResult(
                appliedRevision = updated.revision,
                runRevision = updated.revision,
                eventSequences = sequences
            )
        }
        is UpsertStepTransition -> {
            val current = runs[command.step.runId]
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "run missing")
            AgentJournalRules.requireRevision(current.revision, command.expectedRevision, "run")
            steps[command.step.stepId] = command.step
            val updated = current.copy(revision = current.revision + 1, updatedAtMillis = clock.nowMillis())
            runs[current.runId] = updated
            AgentCommitResult(appliedRevision = updated.revision, runRevision = updated.revision)
        }
        is SupersedeRunTransition -> {
            val current = runs[command.previousRunId]
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "run missing")
            AgentJournalRules.requireRevision(current.revision, command.expectedRevision, "run")
            if (command.updatedRun.state != AgentRunState.SUPERSEDED) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "superseded run must be SUPERSEDED"
                )
            }
            val updated = command.updatedRun.copy(
                revision = current.revision + 1,
                updatedAtMillis = clock.nowMillis()
            )
            runs[updated.runId] = updated
            AgentCommitResult(appliedRevision = updated.revision, runRevision = updated.revision)
        }
        else -> throw UnsupportedOperationException(
            "in-memory journal test double does not model ${command::class.simpleName}"
        )
    }

    /** Test-only seeding for recovery-list cases that are not transition-driven. */
    fun seed(task: AgentTask, run: AgentRun) {
        tasks[task.taskId] = task
        runs[run.runId] = run
    }

    fun currentRun(runId: String): AgentRun? = runs[runId]

    fun currentTask(taskId: String): AgentTask? = tasks[taskId]

    fun commandCount(): Int = commands.size

    companion object {
        private var counter = 1_000L
    }
}

package com.muyuchat.core.agent.contract

import kotlinx.coroutines.flow.Flow

/**
 * Read-only workspace projection. Observing a task or run never starts model work and never
 * mutates state; the same values are obtainable after process recovery from Room.
 */
interface AgentWorkspaceQueries {
    fun observeTasks(query: AgentTaskQuery): Flow<AgentTaskPage>

    fun observeRun(runId: String): Flow<AgentRunSnapshot>

    fun observeEvents(runId: String, afterSequence: Long, limit: Int): Flow<AgentEventPage>
}

/**
 * Narrow command surface used by the independent Agent page.
 *
 * Every command carries a commandId and the applicable expected revision. The owner performs
 * idempotency lookup before revision CAS; retrying the same content returns the original result.
 * Import/export, deletion and extension management stay separate use cases with their own callers.
 */
interface AgentWorkspaceCommands {
    suspend fun createTask(command: CreateAgentTask): AgentCommandResult

    suspend fun startRun(command: StartAgentRun): AgentCommandResult

    suspend fun requestPause(command: PauseAgentRun): AgentCommandResult

    suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult

    suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult

    suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult

    suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult
}

/**
 * Whether the catalog entry is loaded by a runtime that can execute it right now.
 *
 * [UNLOADED] means "installed but not loaded": the model stays visible so the user can find it,
 * but it is not a runnable binding. Loading it remains the existing engine/Chat-page path; the
 * Agent page must not load, lease or fake a loaded state for it.
 */
enum class AgentModelLoadState {
    LOADED,
    UNLOADED
}

/**
 * Read-only model catalog entry for the Agent page's model picker.
 *
 * [loadState] defaults to [AgentModelLoadState.LOADED] so pre-existing catalog implementations and
 * fixtures keep their exact previous (runnable) meaning. This type is a read-only port projection,
 * not a persisted or versioned record, so adding the defaulted field needs no SCHEMA_VERSION bump.
 */
data class AgentModelChoice(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val runtime: String,
    val protocolMode: AgentProtocolMode,
    val assetFingerprint: String,
    val availableOffline: Boolean,
    val loadState: AgentModelLoadState = AgentModelLoadState.LOADED
)

interface AgentModelCatalogPort {
    fun observeModelChoices(): Flow<List<AgentModelChoice>>
}

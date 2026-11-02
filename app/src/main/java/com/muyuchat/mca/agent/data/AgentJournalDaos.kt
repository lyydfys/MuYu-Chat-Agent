package com.muyuchat.mca.agent.data

/**
 * DAO bundle for the Agent journal.
 *
 * Grouping the twelve narrowly-scoped DAOs keeps [RoomAgentJournal]'s public surface to
 * (bundle, transaction runner, clock) instead of a twelve-argument constructor. Each DAO still owns
 * exactly one table family; the bundle is not a service locator and holds no business logic.
 */
data class AgentJournalDaos(
    val task: AgentTaskDao,
    val workspace: AgentWorkspaceDao,
    val run: AgentRunDao,
    val step: AgentStepDao,
    val attempt: AgentModelAttemptDao,
    val invocation: AgentInvocationDao,
    val approval: AgentApprovalDao,
    val event: AgentEventDao,
    val artifact: AgentArtifactDao,
    val artifactCommit: AgentArtifactCommitDao,
    val question: AgentQuestionDao,
    val command: AgentCommandDao
)

package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.UpdateRunTransition

/**
 * Journal decorator that reports every committed run snapshot after the delegate transaction has
 * succeeded. The composition uses it to drive the foreground-service guard and the notification stop
 * router without polling Room and without teaching the runtime about Android.
 *
 * Notification is deliberately after the delegate returns: a failed commit must never acquire or
 * retain a process-protection lease. Observer exceptions are swallowed so platform bookkeeping can
 * never turn a committed Agent transition into a failed command.
 */
class ObservingAgentJournal(
    private val delegate: AgentJournal,
    private val onRunCommitted: (AgentRun) -> Unit
) : AgentJournal by delegate {
    override suspend fun commit(command: AgentTransition): AgentCommitResult {
        val result = delegate.commit(command)
        val committedRun = when (command) {
            is UpdateRunTransition -> command.run
            is CreateRunTransition -> command.run
            else -> null
        }
        if (committedRun != null) {
            runCatching { onRunCommitted(committedRun) }
        }
        return result
    }
}

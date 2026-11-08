package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor

/**
 * Explicit empty surface for a prompt-only same-root frame: no tool can be proposed or executed.
 *
 * Lives here rather than nested in the coordinator because that file is at its architectural line
 * ceiling and this is a self-contained value, not loop logic.
 */
internal object PromptOnlyFrameCatalog : AgentToolCatalog {
    override fun find(toolKey: String): AgentToolExecutor? = null

    override fun snapshot(): List<AgentToolDescriptor> = emptyList()
}

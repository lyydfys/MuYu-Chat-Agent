package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentKnowledgePort
import com.muyuchat.core.agent.contract.AgentPreferencePort
import kotlinx.coroutines.CancellationException

/**
 * Degrading reads of the two optional prompt fragments a step contributes.
 *
 * Both ports are app-owned code invoked on the loop's own coroutine, so a throw or a blank result
 * must degrade to "no fragment" rather than failing the run or leaking a half-built prompt. The
 * prompt builder enforces its own character cap, so these helpers only have to be safe.
 *
 * Extension functions rather than coordinator members: the coordinator is at its architectural line
 * ceiling, these read nothing else from it, and keeping the degradation policy in one file means the
 * two fragments cannot drift into different error behaviour.
 */
internal fun AgentKnowledgePort.relevantKnowledgeSafely(goal: String): String = try {
    relevantKnowledge(goal)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ""
}

internal fun AgentPreferencePort.preferenceTextSafely(): String = try {
    preferenceText()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ""
}

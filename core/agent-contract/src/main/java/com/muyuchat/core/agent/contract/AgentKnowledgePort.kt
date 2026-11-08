package com.muyuchat.core.agent.contract

/**
 * Optional "operation knowledge" a run may receive in its SYSTEM prompt.
 *
 * The knowledge itself is bundled with the app (how to drive WeChat, system settings, a browser
 * page) and lives outside the agent runtime: the runtime must not import app code, and the app must
 * not be able to reach into the loop. So the two meet on this one-method port, in the same
 * direction as every other runtime seam ([AgentWorkspaceFilePort], [AgentToolPort]): the runtime
 * asks, the app answers, and a bare runtime (tests, workflow frames) simply does not wire it.
 *
 * Contract, deliberately narrow:
 *  - **Text only.** [relevantKnowledge] returns a prompt fragment, never a tool, path or permission.
 *    Returning knowledge must not grant anything: the permission broker stays authoritative.
 *  - **Empty means "nothing to add".** The default implementation returns `""`, which leaves the
 *    SYSTEM prompt byte-identical to the pre-port behaviour. That is the regression guarantee the
 *    loop's prompt is unchanged whenever the feature is absent or finds no match.
 *  - **Bounded by the caller.** The implementation is expected to self-limit, and the builder still
 *    enforces its own character cap, so a misbehaving port can only lose text, not the prompt.
 *  - **Pure and cheap.** Called once per step, on the loop's coroutine, without I/O.
 */
fun interface AgentKnowledgePort {
    /** Related operation knowledge for [query] (the run's goal), or `""` when there is none. */
    fun relevantKnowledge(query: String): String

    companion object {
        /** No knowledge: the SYSTEM prompt is exactly what it was before this port existed. */
        val NONE: AgentKnowledgePort = AgentKnowledgePort { "" }
    }
}

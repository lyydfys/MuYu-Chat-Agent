package com.muyuchat.core.agent.contract

/**
 * Optional "user preferences" a run may receive in its SYSTEM prompt.
 *
 * Same shape and direction as [AgentKnowledgePort]: the runtime asks, the app answers, and a bare
 * runtime (tests, workflow frames) simply does not wire it. The two are separate ports rather than
 * one "context" port because they have different lifecycles — bundled operation knowledge is static
 * and ships with the APK, while preferences are learned from what the user says and change during a
 * session.
 *
 * Contract, deliberately narrow:
 *  - **Text only.** [preferenceText] returns a prompt fragment, never a tool, path or permission.
 *    Stating a preference must not grant anything: the permission broker stays authoritative.
 *  - **Empty means "nothing to add".** The default returns `""`, which leaves the SYSTEM prompt
 *    byte-identical to the pre-port behaviour.
 *  - **Bounded by the caller.** The implementation self-limits and the builder enforces its own cap.
 *  - **Pure and cheap.** Called once per step, on the loop's coroutine, without I/O.
 *  - **Never secrets.** An implementation must not surface passwords, verification codes or card
 *    numbers even if the user typed them while stating a preference.
 */
fun interface AgentPreferencePort {
    /** Learned preferences as one prompt fragment, or `""` when there are none. */
    fun preferenceText(): String

    companion object {
        /** No preferences: the SYSTEM prompt is exactly what it was before this port existed. */
        val NONE: AgentPreferencePort = AgentPreferencePort { "" }
    }
}

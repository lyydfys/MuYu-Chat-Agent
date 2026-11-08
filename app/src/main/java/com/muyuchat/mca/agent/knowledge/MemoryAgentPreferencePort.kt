package com.muyuchat.mca.agent.knowledge

import com.muyuchat.core.agent.contract.AgentPreferencePort

/**
 * Production [AgentPreferencePort]: the user's explicitly stated preferences, as prompt text.
 *
 * The store is the source of truth for *what* is preferred; this adapter only decides how it reaches
 * the prompt:
 *
 *  - **Bounded at the boundary.** [AgentPreferenceMemory.asPromptText] is called with the same 1000
 *    character budget the prompt builder enforces, so the two caps agree instead of the builder
 *    chopping a block the store already shaped.
 *  - **Cheap per step.** The loop asks once per step. The rendered text is cached until the memory
 *    changes, so a ten-step run renders it once rather than ten times.
 *  - **Honest emptiness.** With no preference stored the answer is `""`, which is what keeps the
 *    SYSTEM prompt byte-identical for every user who has stated nothing.
 *  - **Never throws.** A failure degrades to "no preferences" rather than failing a run; the loop's
 *    own resolver repeats that guard, but a port should not rely on its caller.
 */
class MemoryAgentPreferencePort(
    private val memory: AgentPreferenceMemory,
    private val maxChars: Int = AgentPreferenceMemory.DEFAULT_PROMPT_CHARS
) : AgentPreferencePort {

    private val lock = Any()
    private var cachedSignature: Int = Int.MIN_VALUE
    private var cachedText: String = ""

    override fun preferenceText(): String {
        val signature = runCatching { memory.size }.getOrDefault(0)
        synchronized(lock) {
            if (signature == cachedSignature) return cachedText
            val rendered = runCatching { memory.asPromptText(maxChars) }.getOrDefault("")
            cachedSignature = signature
            cachedText = rendered
            return rendered
        }
    }
}
